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
package org.apache.cassandra.index.sai.iterators;

import java.util.ArrayList;
import java.util.List;

import org.apache.cassandra.dht.Murmur3Partitioner;
import org.apache.cassandra.index.sai.SAITester;
import org.apache.cassandra.index.sai.utils.PrimaryKey;

public class LongIterator extends KeyRangeIterator
{
    private final List<PrimaryKey> keys;
    private int currentIdx = 0;

    public static LongIterator newEmptyIterator()
    {
        return new LongIterator();
    }

    private LongIterator()
    {
        super(null, null, 0);
        keys = null;
    }

    public LongIterator(long[] tokens)
    {
        super(tokens.length == 0 ? null : fromToken(tokens[0]), tokens.length == 0 ? null : fromToken(tokens[tokens.length - 1]), tokens.length);

        this.keys = new ArrayList<>(tokens.length);
        for (long token : tokens)
            this.keys.add(fromToken(token));
    }

    @Override
    protected PrimaryKey computeNext()
    {
        if (currentIdx >= keys.size())
            return endOfData();

        return keys.get(currentIdx++);
    }

    @Override
    protected void performSkipTo(PrimaryKey nextKey)
    {
        for ( ; currentIdx < keys.size(); currentIdx++)
        {
            PrimaryKey key = keys.get(currentIdx);
            if (key.compareTo(nextKey, false) >= 0)
                break;
        }
    }

    @Override
    public void close()
    {}

    public static PrimaryKey fromToken(long token)
    {
        return SAITester.TEST_FACTORY.create(new Murmur3Partitioner.LongToken(token));
    }


    public static List<Long> convert(KeyRangeIterator tokens)
    {
        List<Long> results = new ArrayList<>();
        while (tokens.hasNext())
            results.add(tokens.next().token().getLongValue());

        return results;
    }

    public static List<Long> convert(final long... nums)
    {
        return new ArrayList<>(nums.length)
        {{
            for (long n : nums)
                add(n);
        }};
    }
}
