package com.praveen.multicloud.repository;

public interface CloudProviderStorageUsage {
    Long getProviderId();
    String getProviderName();
    Long getTotalChunks();
    Long getTotalStorageUsed();
}
