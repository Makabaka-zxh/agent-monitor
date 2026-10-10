package android.content.pm;

/** Returns only fixture metadata; it never parses an APK or reads installed applications. */
public final class PackageManager {
    public static final int GET_SIGNATURES = 64, GET_SIGNING_CERTIFICATES = 134217728;
    public PackageInfo installed, archive;
    public int installedReads, archiveReads, lastFlags;
    public String archivePath;
    public Runnable onArchive;

    public PackageInfo getPackageInfo(String name, int flags) {
        installedReads++; lastFlags = flags; return installed;
    }
    public PackageInfo getPackageArchiveInfo(String path, int flags) {
        archiveReads++; archivePath = path; lastFlags = flags;
        if (onArchive != null) onArchive.run();
        return archive;
    }
}
