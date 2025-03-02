/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.cassandra.index.sai.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import io.netty.util.concurrent.FastThreadLocal;
import org.apache.cassandra.db.Clustering;
import org.apache.cassandra.db.ColumnFamilyStore;
import org.apache.cassandra.db.DataRange;
import org.apache.cassandra.db.DecoratedKey;
import org.apache.cassandra.db.PartitionPosition;
import org.apache.cassandra.db.ReadCommand;
import org.apache.cassandra.db.ReadExecutionController;
import org.apache.cassandra.db.RegularAndStaticColumns;
import org.apache.cassandra.db.filter.RowFilter;
import org.apache.cassandra.db.partitions.PartitionIterator;
import org.apache.cassandra.db.partitions.UnfilteredPartitionIterator;
import org.apache.cassandra.db.rows.AbstractUnfilteredRowIterator;
import org.apache.cassandra.db.rows.Row;
import org.apache.cassandra.db.rows.RowIterator;
import org.apache.cassandra.db.rows.Unfiltered;
import org.apache.cassandra.db.rows.UnfilteredRowIterator;
import org.apache.cassandra.dht.AbstractBounds;
import org.apache.cassandra.dht.Token;
import org.apache.cassandra.exceptions.RequestTimeoutException;
import org.apache.cassandra.index.Index;
import org.apache.cassandra.index.sai.QueryContext;
import org.apache.cassandra.index.sai.metrics.TableQueryMetrics;
import org.apache.cassandra.index.sai.iterators.KeyRangeIterator;
import org.apache.cassandra.index.sai.utils.PrimaryKey;
import org.apache.cassandra.io.util.FileUtils;
import org.apache.cassandra.schema.TableMetadata;
import org.apache.cassandra.utils.AbstractIterator;
import org.apache.cassandra.utils.Clock;

public class StorageAttachedIndexSearcher implements Index.Searcher
{
    private static final int PARTITION_ROW_BATCH_SIZE = 100;

    private final ReadCommand command;
    private final QueryController queryController;
    private final QueryContext queryContext;
    private final TableQueryMetrics tableQueryMetrics;

    private static final FastThreadLocal<List<PrimaryKey>> nextKeys = new FastThreadLocal<>()
    {
        @Override
        protected List<PrimaryKey> initialValue()
        {
            return new ArrayList<>(PARTITION_ROW_BATCH_SIZE);
        }
    };

    public StorageAttachedIndexSearcher(ColumnFamilyStore cfs,
                                        TableQueryMetrics tableQueryMetrics,
                                        ReadCommand command,
                                        RowFilter indexFilter,
                                        long executionQuotaMs)
    {
        this.command = command;
        this.queryContext = new QueryContext(command, executionQuotaMs);
        this.queryController = new QueryController(cfs, command, indexFilter, queryContext);
        this.tableQueryMetrics = tableQueryMetrics;
    }

    @Override
    public ReadCommand command()
    {
        return command;
    }

    @Override
    public PartitionIterator filterReplicaFilteringProtection(PartitionIterator fullResponse)
    {
        for (RowFilter.Expression expression : queryController.indexFilter())
        {
            if (queryController.hasAnalyzer(expression))
                return applyIndexFilter(fullResponse, Operation.buildFilter(queryController, true), queryContext);
        }

        // if no analyzer does transformation
        return Index.Searcher.super.filterReplicaFilteringProtection(fullResponse);
    }

    @Override
    public UnfilteredPartitionIterator search(ReadExecutionController executionController) throws RequestTimeoutException
    {
        if (!command.isTopK())
            return new ResultRetriever(executionController, false);
        else
        {
            Supplier<ResultRetriever> resultSupplier = () -> new ResultRetriever(executionController, true);

            // VSTODO performance: if there is shadowed primary keys, we have to at least query twice.
            //  First time to find out there are shadow keys, second time to find out there are no more shadow keys.
            while (true)
            {
                long lastShadowedKeysCount = queryContext.vectorContext().getShadowedPrimaryKeys().size();
                ResultRetriever result = resultSupplier.get();
                UnfilteredPartitionIterator topK = (UnfilteredPartitionIterator) new VectorTopKProcessor(command).filter(result);

                long currentShadowedKeysCount = queryContext.vectorContext().getShadowedPrimaryKeys().size();
                if (lastShadowedKeysCount == currentShadowedKeysCount)
                    return topK;
            }
        }
    }

    private class ResultRetriever extends AbstractIterator<UnfilteredRowIterator> implements UnfilteredPartitionIterator
    {
        private final PrimaryKey firstPrimaryKey;
        private final PrimaryKey lastPrimaryKey;
        private final Iterator<DataRange> keyRanges;
        private AbstractBounds<PartitionPosition> currentKeyRange;

        private final KeyRangeIterator resultKeyIterator;
        private final FilterTree filterTree;
        private final ReadExecutionController executionController;
        private final PrimaryKey.Factory keyFactory;
        private final boolean topK;
        private final int partitionRowBatchSize;

        private PrimaryKey lastKey;

        private ResultRetriever(ReadExecutionController executionController, boolean topK)
        {
            this.keyRanges = queryController.dataRanges().iterator();
            this.currentKeyRange = keyRanges.next().keyRange();
            this.resultKeyIterator = Operation.buildIterator(queryController);
            this.filterTree = Operation.buildFilter(queryController, queryController.usesStrictFiltering());
            this.executionController = executionController;
            this.keyFactory = queryController.primaryKeyFactory();
            this.firstPrimaryKey = queryController.firstPrimaryKeyInRange();
            this.lastPrimaryKey = queryController.lastPrimaryKeyInRange();
            this.topK = topK;

            // Ensure we don't fetch larger batches than the provided LIMIT to avoid fetching keys we won't use: 
            this.partitionRowBatchSize = Math.min(PARTITION_ROW_BATCH_SIZE, command.limits().count());
        }

        @Override
        public UnfilteredRowIterator computeNext()
        {
            if (resultKeyIterator == null)
                return endOfData();

            // If being called for the first time, skip to the beginning of the range.
            // We can't put this code in the constructor because it may throw and the caller
            // may not be prepared for that.
            if (lastKey == null)
                resultKeyIterator.skipTo(firstPrimaryKey);

            // Theoretically we wouldn't need this if the caller of computeNext always ran the
            // returned iterators to the completion. Unfortunately, we have no control over the caller behavior here.
            // Hence, we skip to the next partition in order to comply to the unwritten partition iterator contract
            // saying this iterator must not return the same partition twice.
            skipToNextPartition();

            UnfilteredRowIterator iterator = nextRowIterator(this::nextSelectedKeysInRange);
            return iterator != null ? iteratePartition(iterator) : endOfData();
        }

        /**
         * Tries to obtain a row iterator for the supplied keys by repeatedly calling
         * {@link ResultRetriever#queryStorageAndFilter} until it gives a non-null result.
         * The keysSupplier should return the next batch of keys with every call to get()
         * and null when there are no more keys to try.
         *
         * @return an iterator or null if all keys were tried with no success
         */
        private @Nullable UnfilteredRowIterator nextRowIterator(@Nonnull Supplier<List<PrimaryKey>> keysSupplier)
        {
            UnfilteredRowIterator iterator = null;
            while (iterator == null)
            {
                List<PrimaryKey> keys = keysSupplier.get();
                if (keys.isEmpty())
                    return null;
                iterator = queryStorageAndFilter(keys);
            }
            return iterator;
        }

        /**
         * Retrieves the next batch of primary keys (i.e. up to {@link #partitionRowBatchSize} of them) that are
         * contained by one of the query key ranges and selected by the {@link QueryController}. If the next key falls
         * out of the current key range, it skips to the next key range, and so on. If no more keys accepted by
         * the controller are available, and empty list is returned.
         *
         * @return a list of up to {@link #partitionRowBatchSize} primary keys
         */
        private List<PrimaryKey> nextSelectedKeysInRange()
        {
            List<PrimaryKey> threadLocalNextKeys = nextKeys.get();
            threadLocalNextKeys.clear();
            PrimaryKey firstKey;

            do
            {
                firstKey = nextKeyInRange();

                if (firstKey == null)
                    return Collections.emptyList();
            }
            while (queryController.doesNotSelect(firstKey) || firstKey.equals(lastKey));

            lastKey = firstKey;
            threadLocalNextKeys.add(firstKey);
            fillNextSelectedKeysInPartition(firstKey.partitionKey(), threadLocalNextKeys);
            return threadLocalNextKeys;
        }

        /**
         * Retrieves the next batch of primary keys (i.e. up to {@link #partitionRowBatchSize} of them) that belong to 
         * the given partition and are selected by the query controller, advancing the underlying iterator only while
         * the next key belongs to that partition.
         *
         * @return a list of up to {@link #partitionRowBatchSize} primary keys within the given partition
         */
        private List<PrimaryKey> nextSelectedKeysInPartition(DecoratedKey partitionKey)
        {
            List<PrimaryKey> threadLocalNextKeys = nextKeys.get();
            threadLocalNextKeys.clear();
            fillNextSelectedKeysInPartition(partitionKey, threadLocalNextKeys);
            return threadLocalNextKeys;
        }

        /**
         * Returns the next available key contained by one of the keyRanges.
         * If the next key falls out of the current key range, it skips to the next key range, and so on.
         * If no more keys or no more ranges are available, returns null.
         */
        private @Nullable PrimaryKey nextKeyInRange()
        {
            PrimaryKey key = nextKey();

            while (key != null && !(currentKeyRange.contains(key.partitionKey())))
            {
                if (!currentKeyRange.right.isMinimum() && currentKeyRange.right.compareTo(key.partitionKey()) <= 0)
                {
                    // currentKeyRange before the currentKey so need to move currentKeyRange forward
                    currentKeyRange = nextKeyRange();
                    if (currentKeyRange == null)
                        return null;
                }
                else
                {
                    // key either before the current range, so let's move the key forward
                    skipTo(currentKeyRange.left.getToken());
                    key = nextKey();
                }
            }
            return key;
        }

        private void fillNextSelectedKeysInPartition(DecoratedKey partitionKey, List<PrimaryKey> nextPrimaryKeys)
        {
            while (resultKeyIterator.hasNext()
                   && resultKeyIterator.peek().partitionKey().equals(partitionKey)
                   && nextPrimaryKeys.size() < partitionRowBatchSize)
            {
                PrimaryKey key = nextKey();

                if (key == null)
                    break;

                if (queryController.doesNotSelect(key) || key.equals(lastKey))
                    continue;

                nextPrimaryKeys.add(key);
                lastKey = key;
            }
        }

        /**
         * Gets the next key from the underlying operation.
         * Returns null if there are no more keys <= lastPrimaryKey.
         */
        private @Nullable PrimaryKey nextKey()
        {
            if (!resultKeyIterator.hasNext())
                return null;
            PrimaryKey key = resultKeyIterator.next();
            return isWithinUpperBound(key) ? key : null;
        }

        /**
         * Returns true if the key is not greater than lastPrimaryKey
         */
        private boolean isWithinUpperBound(PrimaryKey key)
        {
            return lastPrimaryKey.token().isMinimum() || lastPrimaryKey.compareTo(key, false) >= 0;
        }

        /**
         * Gets the next key range from the underlying range iterator.
         */
        private @Nullable AbstractBounds<PartitionPosition> nextKeyRange()
        {
            return keyRanges.hasNext() ? keyRanges.next().keyRange() : null;
        }

        /**
         * Convenience function to skip to a given token.
         */
        private void skipTo(@Nonnull Token token)
        {
            resultKeyIterator.skipTo(keyFactory.create(token));
        }

        /**
         * Skips to the key that belongs to a different partition than the last key we fetched.
         */
        private void skipToNextPartition()
        {
            if (lastKey == null)
                return;
            DecoratedKey lastPartitionKey = lastKey.partitionKey();
            while (resultKeyIterator.hasNext() && resultKeyIterator.peek().partitionKey().equals(lastPartitionKey))
                resultKeyIterator.next();
        }


        /**
         * Returns an iterator over the rows in the partition associated with the given iterator.
         * Initially, it retrieves the rows from the given iterator until it runs out of data.
         * Then it iterates the remaining primary keys obtained from the index in batches until the end of the 
         * partition, lazily constructing an itertor for each batch. Only one row iterator is open at a time.
         * <p>
         * The rows are retrieved in the order of primary keys provided by the underlying index.
         * The iterator is complete when the next key to be fetched belongs to different partition
         * (but the iterator does not consume that key).
         *
         * @param startIter an iterator positioned at the first row in the partition that we want to return
         */
        private @Nonnull UnfilteredRowIterator iteratePartition(@Nonnull UnfilteredRowIterator startIter)
        {
            return new AbstractUnfilteredRowIterator(startIter.metadata(),
                                                     startIter.partitionKey(),
                                                     startIter.partitionLevelDeletion(),
                                                     startIter.columns(),
                                                     startIter.staticRow(),
                                                     startIter.isReverseOrder(),
                                                     startIter.stats())
            {
                private UnfilteredRowIterator currentIter = startIter;
                private final DecoratedKey partitionKey = startIter.partitionKey();

                @Override
                protected Unfiltered computeNext()
                {
                    while (!currentIter.hasNext())
                    {
                        currentIter.close();
                        currentIter = nextRowIterator(() -> nextSelectedKeysInPartition(partitionKey));
                        if (currentIter == null)
                            return endOfData();
                    }
                    return currentIter.next();
                }

                @Override
                public void close()
                {
                    FileUtils.closeQuietly(currentIter);
                    super.close();
                }
            };
        }

        private UnfilteredRowIterator queryStorageAndFilter(List<PrimaryKey> keys)
        {
            long startTimeNanos = Clock.Global.nanoTime();

            try (UnfilteredRowIterator partition = queryController.queryStorage(keys, executionController))
            {
                queryContext.partitionsRead++;
                queryContext.checkpoint();

                UnfilteredRowIterator filtered = filterPartition(keys, partition, filterTree);

                // Note that we record the duration of the read after post-filtering, which actually 
                // materializes the rows from disk.
                tableQueryMetrics.postFilteringReadLatency.update(Clock.Global.nanoTime() - startTimeNanos, TimeUnit.NANOSECONDS);

                return filtered;
            }
        }

        private UnfilteredRowIterator filterPartition(List<PrimaryKey> keys, UnfilteredRowIterator partition, FilterTree tree)
        {
            Row staticRow = partition.staticRow();
            DecoratedKey partitionKey = partition.partitionKey();
            List<Unfiltered> matches = new ArrayList<>();
            boolean hasMatch = false;
            Set<PrimaryKey> keysToShadow = topK ? new HashSet<>(keys) : Collections.emptySet();

            while (partition.hasNext())
            {
                Unfiltered unfiltered = partition.next();

                if (unfiltered.isRow())
                {
                    queryContext.rowsFiltered++;

                    if (tree.isSatisfiedBy(partitionKey, (Row) unfiltered, staticRow))
                    {
                        matches.add(unfiltered);
                        hasMatch = true;

                        if (topK)
                        {
                            PrimaryKey shadowed = keyFactory.hasClusteringColumns()
                                                  ? keyFactory.create(partitionKey, ((Row) unfiltered).clustering())
                                                  : keyFactory.create(partitionKey);
                            keysToShadow.remove(shadowed);
                        }
                    }
                }
            }

            // If any non-static rows match the filter, there should be no need to shadow the static primary key:
            if (topK && hasMatch && keyFactory.hasClusteringColumns())
                keysToShadow.remove(keyFactory.create(partitionKey, Clustering.STATIC_CLUSTERING));

            // We may not have any non-static row data to filter...
            if (!hasMatch)
            {
                queryContext.rowsFiltered++;

                if (tree.isSatisfiedBy(partitionKey, staticRow, staticRow))
                {
                    hasMatch = true;

                    if (topK)
                        keysToShadow.clear();
                }
            }

            if (topK && !keysToShadow.isEmpty())
            {
                // Record primary keys shadowed by expired TTLs, row tombstones, or range tombstones:
                queryContext.vectorContext().recordShadowedPrimaryKeys(keysToShadow);
            }

            if (!hasMatch)
            {
                // If there are no matches, return an empty partition. If reconciliation is required at the
                // coordinator, replica filtering protection may make a second round trip to complete its view
                // of the partition.
                return null;
            }

            // Return all matches found, along with the static row... 
            return new PartitionIterator(partition, staticRow, matches.iterator());
        }

        private class PartitionIterator extends AbstractUnfilteredRowIterator
        {
            private final Iterator<Unfiltered> rows;

            public PartitionIterator(UnfilteredRowIterator partition, Row staticRow, Iterator<Unfiltered> rows)
            {
                super(partition.metadata(),
                      partition.partitionKey(),
                      partition.partitionLevelDeletion(),
                      partition.columns(),
                      staticRow,
                      partition.isReverseOrder(),
                      partition.stats());

                this.rows = rows;
            }

            @Override
            protected Unfiltered computeNext()
            {
                return rows.hasNext() ? rows.next() : endOfData();
            }
        }

        @Override
        public TableMetadata metadata()
        {
            return queryController.metadata();
        }

        @Override
        public void close()
        {
            FileUtils.closeQuietly(resultKeyIterator);
            if (tableQueryMetrics != null) tableQueryMetrics.record(queryContext);
        }
    }

    /**
     * Used by {@link StorageAttachedIndexSearcher#filterReplicaFilteringProtection} to filter rows for columns that
     * have transformations so won't get handled correctly by the row filter.
     */
    private static PartitionIterator applyIndexFilter(PartitionIterator response, FilterTree tree, QueryContext context)
    {
        return new PartitionIterator()
        {
            @Override
            public void close()
            {
                response.close();
            }

            @Override
            public boolean hasNext()
            {
                return response.hasNext();
            }

            @Override
            public RowIterator next()
            {
                RowIterator delegate = response.next();
                Row staticRow = delegate.staticRow();

                // If we only restrict static columns, and we pass the filter, simply pass through the delegate, as all
                // non-static rows are matches. If we fail on the filter, no rows are matches, so return nothing.
                if (!tree.restrictsNonStaticRow())
                    return tree.isSatisfiedBy(delegate.partitionKey(), staticRow, staticRow) ? delegate : null;

                return new RowIterator()
                {
                    Row next;

                    @Override
                    public TableMetadata metadata()
                    {
                        return delegate.metadata();
                    }

                    @Override
                    public boolean isReverseOrder()
                    {
                        return delegate.isReverseOrder();
                    }

                    @Override
                    public RegularAndStaticColumns columns()
                    {
                        return delegate.columns();
                    }

                    @Override
                    public DecoratedKey partitionKey()
                    {
                        return delegate.partitionKey();
                    }

                    @Override
                    public Row staticRow()
                    {
                        return staticRow;
                    }

                    @Override
                    public void close()
                    {
                        delegate.close();
                    }

                    private Row computeNext()
                    {
                        while (delegate.hasNext())
                        {
                            Row row = delegate.next();
                            context.rowsFiltered++;
                            if (tree.isSatisfiedBy(delegate.partitionKey(), row, staticRow))
                                return row;
                        }
                        return null;
                    }

                    private Row loadNext()
                    {
                        if (next == null)
                            next = computeNext();
                        return next;
                    }

                    @Override
                    public boolean hasNext()
                    {
                        return loadNext() != null;
                    }

                    @Override
                    public Row next()
                    {
                        Row result = loadNext();
                        next = null;

                        if (result == null)
                            throw new NoSuchElementException();

                        return result;
                    }
                };
            }
        };
    }
}
