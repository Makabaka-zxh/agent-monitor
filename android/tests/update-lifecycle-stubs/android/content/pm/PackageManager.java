package android.content.pm;
import java.util.*;
import android.content.Intent;
/** Synthetic identities and installer resolution; no real packages are queried or parsed. */
public class PackageManager {
    public static final int MATCH_DEFAULT_ONLY=1,MATCH_SYSTEM_ONLY=2,GET_SIGNING_CERTIFICATES=4,GET_SIGNATURES=8;
    public static final PackageManager instance=new PackageManager();
    public volatile PackageInfo current,archive;
    public volatile int installedReads,identityReads,archiveReads;
    public boolean permission=true,failInstalled;
    public void reset() {
        installedReads=identityReads=archiveReads=0;
        permission=true;
        failInstalled=false;
        current=null;
        archive=null;
    }
    public boolean canRequestPackageInstalls() {
        return permission;
    }
    public PackageInfo getPackageInfo(String p,int flags) throws Exception {
        installedReads++;
        if(flags==0)identityReads++;
        if(failInstalled)throw new Exception("synthetic package read failure");
        return current;
    }
    public PackageInfo getPackageArchiveInfo(String p,int flags) {
        archiveReads++;
        return archive;
    }
    public List<ResolveInfo> queryIntentActivities(Intent i,int flags) {
        ResolveInfo r=new ResolveInfo();
        r.activityInfo=new ActivityInfo();
        r.activityInfo.packageName="synthetic.system.installer";
        r.activityInfo.name="Installer";
        return Arrays.asList(r);
    }
}
