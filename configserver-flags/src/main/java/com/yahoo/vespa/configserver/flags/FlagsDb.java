// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.configserver.flags;

import com.yahoo.vespa.flags.FlagId;
import com.yahoo.vespa.flags.json.FlagData;

import java.util.Map;
import java.util.Optional;

/**
 * @author hakonhall
 */
public interface FlagsDb {
    /** Get the String value of the flag. */
    Optional<FlagData> getValue(FlagId flagId);

    /** Set the String value of the flag. */
    void setValue(FlagId flagId, FlagData data);

    /** Remove the flag value if it exists. */
    void removeValue(FlagId flagId);

    /** Get all flags that have been set. */
    Map<FlagId, FlagData> getAllFlags();
}
