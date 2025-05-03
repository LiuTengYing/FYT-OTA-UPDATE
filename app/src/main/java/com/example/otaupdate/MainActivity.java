package com.example.otaupdate;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.alibaba.sdk.android.oss.internal.OSSAsyncTask;
import com.alibaba.sdk.android.oss.model.GetObjectResult;
import com.example.otaupdate.databinding.ActivityMainBinding;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final String ROOT_UPDATE_DIR = "/storage/emulated/0/";

    private ActivityMainBinding binding;
    private OssManager ossManager;
    private UpdateInfo currentSystemUpdateInfo = null;
    private UpdateInfo currentMcuUpdateInfo = null;

    private String deviceCpuModel = "";
    private String deviceResolution = "";
    private String deviceSystemBuildDate = "";
    private String deviceMcuVersion = "";

    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    @Nullable
    private OSSAsyncTask<GetObjectResult> currentDownloadTask = null;

    private static boolean isDownloading = false;
    public static boolean isDownloading() { return isDownloading; }
    public static void setDownloading(boolean downloading) { isDownloading = downloading; }

    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean allGranted = true;
                for (Boolean granted : permissions.values()) {
                    if (!granted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    Log.d(TAG, "Storage permissions granted.");
                    fetchInitialInfoAndCheckUpdates();
                } else {
                    Log.e(TAG, "Storage permissions denied.");
                    // 不再直接操作UI元素，由Fragment处理
                    Toast.makeText(this, R.string.status_error_permission, Toast.LENGTH_LONG).show();
                    showPermissionDeniedDialog();
                }
            });
            
    // 显示权限被拒绝对话框
    // 显示权限被拒绝对话框 - 已在类的其他位置定义
    
    // 显示状态信息的辅助方法
    private void showStatus(TextView statusView, String message, boolean isError) {
        if (statusView != null) {
            statusView.setText(message);
            statusView.setTextColor(ContextCompat.getColor(this, 
                isError ? android.R.color.holo_red_light : android.R.color.white));
        }
    }

    // 添加一个标志位记录是否已经处理过返回键事件
    private boolean isBackHandled = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        ossManager = new OssManager(this);
        
        // Register reboot dialog broadcast receiver
        registerReceiver(new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(android.content.Context context, android.content.Intent intent) {
                if ("com.example.otaupdate.SHOW_REBOOT_DIALOG".equals(intent.getAction())) {
                    showManualRebootDialogOnMainThread();
                }
            }
        }, new android.content.IntentFilter("com.example.otaupdate.SHOW_REBOOT_DIALOG"));
        
        // Setup Navigation
        androidx.fragment.app.FragmentContainerView navHostFragment =
                (androidx.fragment.app.FragmentContainerView) findViewById(R.id.nav_host_fragment);
        androidx.navigation.NavController navController =
                ((androidx.navigation.fragment.NavHostFragment) getSupportFragmentManager()
                        .findFragmentById(R.id.nav_host_fragment))
                        .getNavController();
        
        // Setup navigation click listeners
        binding.navDeviceInfo.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_device_info);
            updateNavSelection(binding.navDeviceInfo);
            overrideNavAnimation(false);
        });
        
        binding.navSystemUpdate.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_system_update);
            updateNavSelection(binding.navSystemUpdate);
            overrideNavAnimation(true);
        });
        
        binding.navMcuUpdate.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_mcu_update);
            updateNavSelection(binding.navMcuUpdate);
            overrideNavAnimation(true);
        });
        
        binding.navSystemApp.setOnClickListener(v -> {
            navController.navigate(R.id.navigation_system_app);
            updateNavSelection(binding.navSystemApp);
            overrideNavAnimation(true);
        });
        
        // 添加导航监听器以触发动画
        navController.addOnDestinationChangedListener((controller, destination, arguments) -> {
            // 动画已在点击事件中处理
        });
        
        // Set initial selection
        updateNavSelection(binding.navDeviceInfo);
        navController.navigate(R.id.navigation_device_info);
        
        setupButtonClickListeners();
        setupInitialUIState();
        checkAndRequestPermissions();
    }

    private void updateNavSelection(TextView selected) {
        // Reset all backgrounds
        binding.navDeviceInfo.setSelected(false);
        binding.navSystemUpdate.setSelected(false);
        binding.navMcuUpdate.setSelected(false);
        binding.navSystemApp.setSelected(false);
        
        // Set selected state
        selected.setSelected(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
        if (ossManager != null) {
            ossManager.shutdown();
        }
        if (currentDownloadTask != null && !currentDownloadTask.isCompleted()) {
            currentDownloadTask.cancel();
            Log.d(TAG, "Cancelling download task in onDestroy");
        }
    }

    private void setupButtonClickListeners() {
        // 使用Navigation组件，按钮点击事件在各个Fragment中处理
    }

    private void handleCheckClick(boolean isSystemUpdate) {
        if (hasPermissions()) {
            if (isSystemUpdate) checkSystemUpdate();
            else checkMcuUpdate();
        } else {
            requestPermissions();
        }
    }

    private void handleDownloadClick(UpdateInfo info, boolean isSystemUpdate) {
        if (info != null) {
            if (hasPermissions()) {
                startDownloadAndApply(info, isSystemUpdate);
            } else {
                Log.w(TAG, "Download clicked but permissions not granted.");
                requestPermissions();
                Toast.makeText(this, R.string.permission_request_title, Toast.LENGTH_SHORT).show();
            }
        } else {
            Log.w(TAG, "Download clicked but no update info available.");
            Toast.makeText(this, R.string.status_checking, Toast.LENGTH_SHORT).show();
        }
    }

    private void setupInitialUIState() {
        // 使用Navigation组件，不直接操作UI元素
        // 初始化状态在各个Fragment中处理
    }

    private void checkAndRequestPermissions() {
        if (hasPermissions()) {
            Log.d(TAG, "Storage permissions already granted.");
            fetchInitialInfoAndCheckUpdates();
        } else {
            Log.d(TAG, "Requesting storage permissions.");
            requestPermissions();
        }
    }

    private boolean hasPermissions() {
        boolean hasRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        boolean hasWrite = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        Log.d(TAG, "Checking legacy permissions: READ=" + hasRead + ", WRITE=" + hasWrite);
        return hasRead && hasWrite;
    }

    private void requestPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
            perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        if (!perms.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + perms);
            new AlertDialog.Builder(this).setTitle(R.string.permission_request_title).setMessage(R.string.permission_request_message).setPositiveButton(getString(R.string.ok), (dialog, which) -> {
                requestPermissionLauncher.launch(perms.toArray(new String[0]));
            }).setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
                Log.e(TAG, "User declined permission request via dialog");
                showPermissionDeniedDialog();
            }).setCancelable(false).show();
        } else {
            Log.d(TAG, "RequestPermissions called but permissions seem already granted?");
            fetchInitialInfoAndCheckUpdates();
        }
    }

    private void fetchInitialInfoAndCheckUpdates() {
        // 状态显示逻辑已移至各个Fragment
        backgroundExecutor.submit(() -> {
            try {
                Log.d(TAG, "Starting to fetch device info...");
                deviceCpuModel = DeviceInfoUtils.getCpuModel();
                deviceResolution = DeviceInfoUtils.getResolution(MainActivity.this);
                deviceSystemBuildDate = DeviceInfoUtils.getSystemBuildDate();
                deviceMcuVersion = DeviceInfoUtils.getMcuVersion();
                Log.d(TAG, "Device info fetched: CPU=" + deviceCpuModel + ", Res=" + deviceResolution + ", Build=" + deviceSystemBuildDate + ", MCU=" + deviceMcuVersion);
            } catch (Exception e) {
                Log.e(TAG, "Error fetching device info", e);
                if (deviceCpuModel == null || deviceCpuModel.isEmpty())
                    deviceCpuModel = getString(R.string.unknown);
                if (deviceResolution == null || deviceResolution.isEmpty())
                    deviceResolution = getString(R.string.unknown);
                if (deviceSystemBuildDate == null || deviceSystemBuildDate.isEmpty())
                    deviceSystemBuildDate = getString(R.string.unknown);
                deviceMcuVersion = DeviceInfoUtils.getMcuVersion();
            }
            // Device info fetched, can be accessed by Fragments via shared ViewModel
            Log.d(TAG, "Device info fetched, available for Fragments");
        });
    }

    private void checkSystemUpdate() {
        // System update check logic moved to SystemUpdateFragment
        Log.d(TAG, "System update check should be handled in SystemUpdateFragment");
    }
    
    /**
     * 重置UI状态的方法
     * @param showError 是否显示错误状态
     */
    private void resetUiStateOnMainThread(boolean showError) {
        // 此方法现在为空实现，UI状态由各个Fragment自行管理
        Log.d(TAG, "UI state reset requested, delegated to Fragments");
    }
    
    /**
     * 设置按钮启用状态的方法
     * @param enabled 是否启用按钮
     */
    private void setButtonsEnabled(boolean enabled) {
        // 此方法现在为空实现，按钮状态由各个Fragment自行管理
        Log.d(TAG, "Button state change requested, delegated to Fragments");
    }
    
    /**
     * 禁用更新按钮的方法
     */
    private void disableUpdateButtons() {
        Log.d(TAG, "Disable buttons requested, delegated to Fragments");
    }

    private void checkMcuUpdate() {
        // MCU update check logic moved to McuUpdateFragment
        Log.d(TAG, "MCU update check should be handled in McuUpdateFragment");
        // 不再直接在MainActivity中处理MCU更新检查，由McuUpdateFragment处理
        // 保留此方法以保持代码结构一致性
    }

    private void startDownloadAndApply(UpdateInfo updateInfo, boolean isSystemUpdate) {
        if (!hasPermissions()) {
            Log.w(TAG, "Permissions check failed before showing dialog.");
            requestPermissions();
            return;
        }
        new AlertDialog.Builder(this).setTitle(R.string.confirm_reboot_title).setMessage(R.string.confirm_reboot_message).setPositiveButton(R.string.ok, (dialog, which) -> proceedWithDownload(updateInfo, isSystemUpdate)).setNegativeButton(R.string.cancel, null).setCancelable(false).show();
    }

    private void proceedWithDownload(final UpdateInfo updateInfo, final boolean isSystemUpdate) {
        if (!hasPermissions()) {
            Log.e(TAG, "Permissions lost before starting download/move.");
            showPermissionDeniedDialog();
            return;
        }

        // Download UI handling logic moved to respective Fragments
        // 不再直接操作UI元素，由Fragment处理
        final String downloadFileName = updateInfo.getObjectKey().substring(updateInfo.getObjectKey().lastIndexOf('/') + 1);
        final String downloadFilePath = new File(getCacheDir(), downloadFileName).getAbsolutePath();
        final String unzipDirPath = new File(getCacheDir(), "unzipped_" + (isSystemUpdate ? "system" : "mcu")).getAbsolutePath();
        final String targetDirPath = ROOT_UPDATE_DIR;

        if (currentDownloadTask != null && !currentDownloadTask.isCompleted()) {
            currentDownloadTask.cancel();
            Log.d(TAG, "Previous download task cancelled.");
        }

        // Download progress handling logic moved to respective Fragments
        currentDownloadTask = ossManager.downloadUpdate(updateInfo.getObjectKey(), downloadFilePath, new OssManager.DownloadCallback() {
            @Override
            public void onProgress(long currentSize, long totalSize) {
                int progress = (totalSize > 0) ? (int) ((currentSize * 100) / totalSize) : 0;
                Log.d(TAG, "Downloading: " + progress + "%");
                // 进度更新应在Fragment中处理
            }

            @Override
            public void onSuccess() {
                String downloadedFilePath = downloadFilePath;
                currentDownloadTask = null;
                Log.d(TAG, "Download completed: " + downloadFilePath);
                // Download completion handling logic moved to respective Fragments
                backgroundExecutor.submit(() -> {
                    boolean moveSuccess = false;
                    try {
                        Log.d(TAG, "Starting unzip...");
                        FileUtils.deleteRecursive(new File(unzipDirPath));
                        if (!FileUtils.unzip(downloadFilePath, unzipDirPath))
                            throw new IOException("Unzipping failed");
                        Log.d(TAG, "Unzip successful.");
                        
                        // 检查下载的文件名是否包含系统升级包特征
                        boolean isSystemUpdateFile = downloadFileName.matches("\\d+.*\\.zip") || downloadFileName.matches(".*\\d+_\\d+.*\\.zip");
                        if (isSystemUpdate || isSystemUpdateFile) {
                            Log.d(TAG, "Detected system update package: " + downloadFileName);
                        }
                        
                        // Extraction and file moving status update logic moved to respective Fragments
                        Log.d(TAG, "Starting move to " + targetDirPath);
                        moveSuccess = FileUtils.moveFilesFromDirectory(unzipDirPath, targetDirPath);
                        if (!moveSuccess) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                Log.e(TAG, "Moving files failed - Expected on Android 10+ restriction");
                                throw new IOException("Moving files failed (Android 10+ restriction)");
                            } else {
                                Log.e(TAG, "Moving files failed - Check permissions or path.");
                                throw new IOException("Moving files failed.");
                            }
                        }
                        Log.d(TAG, "Moving files successful.");
                        Log.d(TAG, "File move completed successfully");
                        Log.d(TAG, "Preparing for reboot...");
                        Log.d(TAG, "Attempting reboot...");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                        triggerReboot();

                    } catch (Exception e) {
                        Log.e(TAG, "File ops or reboot process failed", e);
                        String errorMsg = getDetailedErrorMessage(e);
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q && !moveSuccess && e instanceof IOException && e.getMessage() != null && e.getMessage().contains("Moving files failed")) {
                            showAndroid10MoveFailDialog();
                        } else if (e instanceof SecurityException && e.getMessage() != null && e.getMessage().contains("REBOOT")) {
                            Log.w(TAG, "Reboot security exception already handled by triggerReboot.");
                        } else {
                            // 使用Fragment中的视图，这里暂时使用日志记录错误
                            Log.e(TAG, "Error during file operations: " + errorMsg);
                            // 不再使用errorStatusView，因为它可能在Fragment中
                        }
                        cleanupTemporaryFiles(downloadFilePath, unzipDirPath);
                        // 不再直接操作UI元素，由Fragment处理
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                currentDownloadTask = null;
                Log.e(TAG, "Download failed", e);
                Log.e(TAG, "Download failed: " + e.getMessage());
                cleanupTemporaryFiles(downloadFilePath, unzipDirPath);
            }
        });
        if (currentDownloadTask == null) {
            Log.e(TAG, "Failed to start download task.");
            // 使用Fragment中的视图，这里暂时使用日志记录错误
            Log.e(TAG, "Failed to start download task.");
            // 不再使用errorStatusView，因为它可能在Fragment中
            // 不再直接操作UI元素，由Fragment处理
        }
    }

    private String getDetailedErrorMessage(Exception e) {
        String baseMsg = getString(R.string.status_error_generic);
        String specificMsg = e.getMessage() != null ? e.getMessage() : e.toString();
        if (e instanceof IOException) {
            if (e.getMessage() != null) {
                if (e.getMessage().contains("Unzipping failed"))
                    return getString(R.string.status_error_unzip, specificMsg);
                if (e.getMessage().contains("Moving files failed"))
                    return getString(R.string.status_error_move, specificMsg);
            }
        } else if (e instanceof SecurityException) {
            return getString(R.string.status_error_reboot);
        }
        return baseMsg + ": " + specificMsg;
    }

    private void triggerReboot() {
        try {
            // 直接使用shell命令重启
            Log.d(TAG, "尝试使用shell命令重启...");
            Runtime.getRuntime().exec("reboot recovery");
            Log.i(TAG, "已执行reboot recovery命令");
            
            // 显示正在重启提示
            Toast.makeText(this, R.string.status_rebooting, Toast.LENGTH_LONG).show();
            
            // 延迟5秒后，如果应用还在运行，才显示手动重启提示
            new Handler().postDelayed(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    // 如果5秒后应用仍在运行，则显示手动重启提示
                    showManualRebootDialogOnMainThread();
                }
            }, 5000);
        } catch (Exception e) {
            Log.e(TAG, "重启命令执行失败", e);
            showManualRebootDialogOnMainThread();
        }
    }

    // 清理临时文件的方法保留，可能在其他地方需要
    private void cleanupTemporaryFiles(String downloadFilePath, String unzipDirPath) {
        try {
            File downloadFile = new File(downloadFilePath);
            if (downloadFile.exists()) {
                boolean deleted = downloadFile.delete();
                Log.d(TAG, "Deleted download file: " + deleted);
            }
            FileUtils.deleteRecursive(new File(unzipDirPath));
            Log.d(TAG, "Cleaned up temporary files");
        } catch (Exception e) {
            Log.e(TAG, "Error cleaning up temporary files", e);
        }
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        NetworkCapabilities cap = cm.getNetworkCapabilities(cm.getActiveNetwork());
        if (cap == null) return false;
        return cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
    }

    private void showPermissionDeniedDialog() {
        new AlertDialog.Builder(this).setTitle(R.string.permission_request_title).setMessage(R.string.permission_request_message).setPositiveButton(R.string.ok, (dialog, which) -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                Uri uri = Uri.fromParts("package", getPackageName(), null);
                intent.setData(uri);
                startActivity(intent);
            } catch (Exception e) {
                Log.e(TAG, "Failed to open app settings", e);
            }
        }).setNegativeButton(getString(R.string.cancel), (dialog, which) -> {
            // Status views will be accessed through fragments
            // 不再直接操作UI元素，由Fragment处理
        }).setCancelable(false).show();
    }

    private void showAndroid10MoveFailDialog() {
        mainThreadHandler.post(() -> {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.error)
                    .setMessage(R.string.permission_request_message_all_files)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            Uri uri = Uri.fromParts("package", getPackageName(), null);
                            intent.setData(uri);
                            startActivity(intent);
                        } catch (Exception e) {
                            Log.e(TAG, "Failed to open all files access settings", e);
                            Toast.makeText(MainActivity.this, getString(R.string.status_error_permission), Toast.LENGTH_LONG).show();
                        }
                    })
                    .setNegativeButton(R.string.cancel, null)
                    .show();
        });
    }

    private void showManualRebootDialogOnMainThread() {
        mainThreadHandler.post(() -> {
            if (isFinishing() || isDestroyed()) {
                return;
            }
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.reboot_dialog_title)
                    .setMessage(R.string.reboot_dialog_message)
                    .setPositiveButton(getString(R.string.reboot_dialog_confirm), (dialog, which) -> {
                        // 现在重启，调用重启方法
                        triggerReboot();
                    })
                    .setNegativeButton(getString(R.string.reboot_dialog_later), (dialog, which) -> {
                        // 用户选择稍后重启，只关闭对话框
                        dialog.dismiss();
                    })
                    .setCancelable(false)
                    .show();
            Log.i(TAG, "Manual reboot dialog shown");
            // Status views will be accessed through fragments
        });
    }

    @Override
    public void onBackPressed() {
        // 日志输出用于调试
        Log.d(TAG, "onBackPressed called");
        
        // 检查是否有下载任务正在进行
        if (isDownloading) {
            // 如果有下载正在进行，弹出提示对话框
            new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_exit_title)
                .setMessage(R.string.confirm_exit_downloading_message)
                .setPositiveButton(R.string.ok, (dialog, which) -> {
                    // 用户确认要退出，取消下载
                    cancelAllDownloads();
                    // 直接调用finish()而不是super.onBackPressed()
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
        } else {
            // 日志输出用于调试
            Log.d(TAG, "Finishing activity directly");
            // 直接调用finish()而不是super.onBackPressed()
            finish();
        }
    }
    
    private void cancelAllDownloads() {
        // 取消所有下载任务
        OssManager ossManager = new OssManager(this);
        ossManager.cancelDownload();
        
        // 重置下载状态
        setDownloading(false);
    }

    // 添加导航动画
    private void overrideNavAnimation(boolean goingRight) {
        if (goingRight) {
            overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
        } else {
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
        }
    }

}