# Copyright 2017 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
vespa_add_library(storage_spersistence OBJECT
    SOURCES
    asynchandler.cpp
    bucketownershipnotifier.cpp
    bucketprocessor.cpp
    fieldvisitor.cpp
    mergehandler.cpp
    messages.cpp
    persistencehandler.cpp
    persistencethread.cpp
    persistenceutil.cpp
    processallhandler.cpp
    provider_error_wrapper.cpp
    simplemessagehandler.cpp
    splitbitdetector.cpp
    splitjoinhandler.cpp
    testandsethelper.cpp
    types.cpp
    DEPENDS
)
