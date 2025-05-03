package com.example.otaupdate; // 确认包名

import java.util.Objects;

public class UpdateInfo {
    private final String version;
    private final String objectKey;
    private final String downloadUrl;

    public UpdateInfo(String version, String objectKey) {
        this(version, objectKey, null);
    }

    public UpdateInfo(String version, String objectKey, String downloadUrl) {
        this.version = version;
        this.objectKey = objectKey;
        this.downloadUrl = downloadUrl;
    }

    public String getVersion() {
        return version;
    }

    public String getObjectKey() {
        return objectKey;
    }
    
    public String getKey() {
        return objectKey;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UpdateInfo that = (UpdateInfo) o;
        return Objects.equals(version, that.version) && Objects.equals(objectKey, that.objectKey) && Objects.equals(downloadUrl, that.downloadUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(version, objectKey, downloadUrl);
    }

    @Override
    public String toString() {
        return "UpdateInfo{" + "version='" + version + '\'' + ", objectKey='" + objectKey + '\'' + ", downloadUrl='" + downloadUrl + '\'' + '}';
    }
}