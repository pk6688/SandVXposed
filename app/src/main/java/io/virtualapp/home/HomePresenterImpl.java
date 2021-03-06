package io.virtualapp.home;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.widget.Toast;

import com.lody.virtual.GmsSupport;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.os.VUserInfo;
import com.lody.virtual.os.VUserManager;
import com.lody.virtual.remote.InstallResult;
import com.lody.virtual.remote.InstalledAppInfo;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import io.virtualapp.R;
import io.virtualapp.VCommends;
import io.virtualapp.abs.ui.VUiKit;
import io.virtualapp.home.models.AppData;
import io.virtualapp.home.models.AppInfoLite;
import io.virtualapp.home.models.MultiplePackageAppData;
import io.virtualapp.home.models.PackageAppData;
import io.virtualapp.home.models.safePackage;
import io.virtualapp.home.repo.AppRepository;
import io.virtualapp.home.repo.PackageAppDataStorage;
import io.virtualapp.home.repo.XAppDataInstalled;
import jonathanfinerty.once.Once;

/**
 * @author Lody
 */
class HomePresenterImpl implements HomeContract.HomePresenter {

    private HomeContract.HomeView mView;
    private Activity mActivity;
    private AppRepository mRepo;
    private AppData mTempAppData;


    HomePresenterImpl(HomeContract.HomeView view) {
        mView = view;
        mActivity = view.getActivity();
        mRepo = new AppRepository(mActivity);
        mView.setPresenter(this);
    }

    @Override
    public void start() {
        dataChanged();
        if (!Once.beenDone(VCommends.TAG_SHOW_ADD_APP_GUIDE)) {
            mView.showGuide();
            Once.markDone(VCommends.TAG_SHOW_ADD_APP_GUIDE);
        }
        if (!Once.beenDone(VCommends.TAG_ASK_INSTALL_GMS) && GmsSupport.isOutsideGoogleFrameworkExist()) {
            mView.askInstallGms();
            Once.markDone(VCommends.TAG_ASK_INSTALL_GMS);
        }
    }

    @Override
    public void launchApp(AppData data) {
        try {
            if (data instanceof PackageAppData) {
                PackageAppData appData = (PackageAppData) data;
                appData.isFirstOpen = false;
                LoadingActivity.launch(mActivity, appData.packageName, 0);
            } else if (data instanceof MultiplePackageAppData) {
                MultiplePackageAppData multipleData = (MultiplePackageAppData) data;
                multipleData.isFirstOpen = false;
                LoadingActivity.launch(mActivity, multipleData.appInfo.packageName, ((MultiplePackageAppData) data).userId);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void dataChanged() {
        mView.showLoading();
        mRepo.getVirtualApps().done(mView::loadFinish).fail(mView::loadError);
    }


    @Override
    public void addApp(AppInfoLite info) {
        class AddResult {
            private PackageAppData appData;
            private int userId;
            private boolean justEnableHidden;
        }
        XAppDataInstalled lpXappData = new XAppDataInstalled();
        try
        {
            lpXappData.pkgName = info.packageName;
            mView.addAppToLauncher(lpXappData);
        }
        catch(Throwable e)
        {
            e.printStackTrace();
            return;
        }
        AddResult addResult = new AddResult();
        VUiKit.defer().when(() -> {
            InstalledAppInfo installedAppInfo = VirtualCore.get().getInstalledAppInfo(info.packageName, 0);
            addResult.justEnableHidden = installedAppInfo != null;
            if (addResult.justEnableHidden) {
                int[] userIds = installedAppInfo.getInstalledUsers();
                int nextUserId = userIds.length;
                /*
                  Input : userIds = {0, 1, 3}
                  Output: nextUserId = 2
                 */
                for (int i = 0; i < userIds.length; i++) {
                    if (userIds[i] != i) {
                        nextUserId = i;
                        break;
                    }
                }
                addResult.userId = nextUserId;

                if (VUserManager.get().getUserInfo(nextUserId) == null) {
                    // user not exist, create it automatically.
                    String nextUserName = "Space " + (nextUserId + 1);
                    VUserInfo newUserInfo = VUserManager.get().createUser(nextUserName, VUserInfo.FLAG_ADMIN);
                    if (newUserInfo == null) {
                        throw new IllegalStateException();
                    }
                }
                List<String> pkg_Det = Arrays.asList(safePackage.safe_Package);
                if (!Once.beenDone("disable_safe_mode")&&!pkg_Det.contains(info.packageName))
                {
                    return;
                }
                else
                {
                    boolean success = VirtualCore.get().installPackageAsUser(nextUserId, info.packageName);
                    if (!success)
                    {
                        throw new IllegalStateException();
                    }
                }
            } else {
                List<String> pkg_Det = Arrays.asList(safePackage.safe_Package);
                if (!Once.beenDone("disable_safe_mode")&&!pkg_Det.contains(info.packageName))
                {
                    return;
                }
                else
                {
                    InstallResult res = mRepo.addVirtualApp(info);
                    if (!res.isSuccess)
                    {
                        throw new IllegalStateException();
                    } else
                    {
                    /*
                    InstalledAppInfo ins = VirtualCore.get().getInstalledAppInfo(info.packageName, 0);
                    if (ins != null && ins.xposedModule != null) {
                        String name = ins.getApplicationInfo(0).name;
                    }
                    */
                    }
                }
            }
        }).then((res) -> {
            try
            {
                addResult.appData = PackageAppDataStorage.get().acquire(info.packageName);
            }
            catch(Throwable e)
            {
                e.printStackTrace();
                mView.removeAppToLauncher(lpXappData);
                return;
            }
        }).done(res -> {
            boolean multipleVersion = addResult.justEnableHidden && addResult.userId != 0;
            try
            {
                if (addResult.appData.getXposedModule() != null)
                {
                    Toast.makeText(mActivity, String.format(mActivity.getString(R.string.module_install_success), addResult.appData.name), Toast.LENGTH_SHORT).show();
                    mView.removeAppToLauncher(lpXappData);
                    return;
                }
            }
            catch(Throwable e)
            {
                e.printStackTrace();
                mView.removeAppToLauncher(lpXappData);
                return;
            }
            mView.removeAppToLauncher(lpXappData);
            if (!multipleVersion) {
                PackageAppData data = addResult.appData;
                data.isLoading = true;
                mView.addAppToLauncher(data);
                handleOptApp(data, info.packageName, true);
            } else {
                MultiplePackageAppData data = new MultiplePackageAppData(addResult.appData, addResult.userId);
                data.isLoading = true;
                mView.addAppToLauncher(data);
                handleOptApp(data, info.packageName, false);
            }
        });
    }


    private void handleOptApp(AppData data, String packageName, boolean needOpt) {
        VUiKit.defer().when(() -> {
            long time = System.currentTimeMillis();
            if (needOpt) {
                try {
                    VirtualCore.get().preOpt(packageName);
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
            time = System.currentTimeMillis() - time;
            if (time < 1500L) {
                try {
                    Thread.sleep(1500L - time);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }).done((res) -> {
            if (data instanceof PackageAppData) {
                ((PackageAppData) data).isLoading = false;
                ((PackageAppData) data).isFirstOpen = true;
            } else if (data instanceof MultiplePackageAppData) {
                ((MultiplePackageAppData) data).isLoading = false;
                ((MultiplePackageAppData) data).isFirstOpen = true;
            }
            mView.refreshLauncherItem(data);
        });
    }

    @Override
    public void deleteApp(AppData data) {
        try {
            mView.removeAppToLauncher(data);
            if (data instanceof PackageAppData) {
                mRepo.removeVirtualApp(((PackageAppData) data).packageName, 0);
            } else {
                MultiplePackageAppData appData = (MultiplePackageAppData) data;
                mRepo.removeVirtualApp(appData.appInfo.packageName, appData.userId);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    @Override
    public void createShortcut(AppData data) {
        VirtualCore.OnEmitShortcutListener listener = new VirtualCore.OnEmitShortcutListener() {
            @Override
            public Bitmap getIcon(Bitmap originIcon) {
                return originIcon;
            }

            @Override
            public String getName(String originName) {
                return originName + "(VA)";
            }
        };
        if (data instanceof PackageAppData) {
            VirtualCore.get().createShortcut(0, ((PackageAppData) data).packageName, listener);
        } else if (data instanceof MultiplePackageAppData) {
            MultiplePackageAppData appData = (MultiplePackageAppData) data;
            VirtualCore.get().createShortcut(appData.userId, appData.appInfo.packageName, listener);
        }
    }

}
