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
package org.apache.cassandra.auth;

import java.util.EnumSet;
import java.util.Set;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

/**
 * An enum encapsulating the set of possible permissions that an authenticated user can have on a resource.
 *
 * IAuthorizer implementations may encode permissions using ordinals, so the Enum order must never change order.
 * Adding new values is ok.
 */
public enum Permission
{
    // schema and role management
    // CREATE, ALTER and DROP permissions granted on an appropriate DataResource are required for
    // CREATE KEYSPACE and CREATE TABLE.
    // ALTER KEYSPACE, ALTER TABLE, CREATE INDEX and DROP INDEX require ALTER permission on the
    // relevant DataResource.
    // DROP KEYSPACE and DROP TABLE require DROP permission.
    //
    // In the context of Role management, these permissions may also be granted on a RoleResource.
    // CREATE is only granted on the root-level role resource, and is required to create new roles.
    // ALTER & DROP may be granted on either the root-level role resource, giving permissions on
    // all roles, or on specific role-level resources.
    CREATE,
    ALTER,
    DROP,

    // data access
    SELECT, // required for SELECT on a table
    MODIFY, // required for INSERT, UPDATE, DELETE, TRUNCATE on a DataResource.

    // permission management
    AUTHORIZE, // required for GRANT and REVOKE of permissions or roles.

    DESCRIBE, // required on the root-level RoleResource to list all Roles

    // UDF permissions
    EXECUTE,  // required to invoke any user defined function or aggregate

    UNMASK, // required to see masked data

    SELECT_MASKED; // required for SELECT on a table with restictions on masked columns

    public static final Set<Permission> ALL =
            Sets.immutableEnumSet(EnumSet.range(Permission.CREATE, Permission.SELECT_MASKED));
    public static final Set<Permission> NONE = ImmutableSet.of();

    /**
     * Set of Permissions which may never be granted on any system keyspace, or table in a system keyspace, to any role.
     * (Only SELECT, DESCRIBE and ALTER may ever be granted).
     */
    public static final Set<Permission> INVALID_FOR_SYSTEM_KEYSPACES =
            Sets.immutableEnumSet(EnumSet.complementOf(EnumSet.of(Permission.SELECT, Permission.DESCRIBE, Permission.ALTER)));
}
