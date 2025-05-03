package com.example.otaupdate.ui.system;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ProgressBar;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.otaupdate.DeviceInfoUtils;
import com.example.otaupdate.DownloadProgressManager;
import com.example.otaupdate.R;
import com.example.otaupdate.UpdateInfo;
import com.example.otaupdate.OssManager;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SystemUpdateFragment extends Fragment {

    private TextView tvCurrentVersion;
    private TextView tvLatestVersion;
    private Button btnCheckUpdate;
    private Button btnDownloadUpdate;
    private View progressContainer;
    private TextView tvUpdateStatus;
    
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    private UpdateInfo currentSystemUpdateInfo = null;
    private String latestOssVersion = null;
    private int downloadClickCount = 0;
    private long lastDownloadClickTime = 0;
    private static final int FORCE_DOWNLOAD_CLICKS = 10;
    private String localSystemVersion = null;
    private boolean canDownload = false;
    private File downloadFile = null;

    // 静态变量，用于在Fragment切换时保存下载状态
    private static boolean isSystemDownloading = false;
    private static int systemDownloadProgress = 0;
    private static String systemDownloadStatus = "";
    
    // 下载进度管理器
    private DownloadProgressManager downloadProgressManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_system_update, container, false);
        
        tvCurrentVersion = view.findViewById(R.id.tvCurrentVersion);
        tvLatestVersion = view.findViewById(R.id.tvLatestVersion);
        progressContainer = view.findViewById(R.id.progressContainer);
        tvUpdateStatus = view.findViewById(R.id.tvUpdateStatus);
        btnCheckUpdate = view.findViewById(R.id.btnCheckUpdate);
        btnDownloadUpdate = view.findViewById(R.id.btnDownloadUpdate);
        
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        btnCheckUpdate.setOnClickListener(v -> checkSystemUpdate());
        btnDownloadUpdate.setOnClickListener(v -> {
            long now = System.currentTimeMillis();
            if (!canDownload) {
                if (now - lastDownloadClickTime < 2000) {
                    downloadClickCount++;
                } else {
                    downloadClickCount = 1;
                }
                lastDownloadClickTime = now;
                if (downloadClickCount >= FORCE_DOWNLOAD_CLICKS) {
                    new AlertDialog.Builder(requireContext())
                        .setTitle(R.string.force_update_title)
                        .setMessage(R.string.force_update_message)
                        .setPositiveButton(R.string.force_update_confirm, (dialog, which) -> {
                            canDownload = true;
                            btnDownloadUpdate.setAlpha(1.0f);
                            showStatus(getString(R.string.status_force_update_unlocked), false);
                        })
                        .setNegativeButton(R.string.force_update_cancel, null)
                        .show();
                    downloadClickCount = 0;
                }
                return;
            }
            downloadUpdate();
        });
        
        // 初始化下载进度管理器
        initDownloadProgressManager(view);
        
        fetchCurrentSystemVersion();
        
        // 恢复下载状态（如果有）
        if (isSystemDownloading) {
            btnCheckUpdate.setEnabled(false);
            btnDownloadUpdate.setEnabled(false);
            
            // 使用下载进度管理器显示进度
            if (downloadProgressManager != null) {
                downloadProgressManager.showProgress();
                downloadProgressManager.updateProgress(
                    systemDownloadProgress * 1024L, // 模拟字节数
                    100 * 1024L,  // 模拟总字节数
                    systemDownloadStatus
                );
            }
        } else {
            if (progressContainer != null) {
                progressContainer.setVisibility(View.GONE);
            }
        }
        
        btnDownloadUpdate.setAlpha(canDownload ? 1.0f : 0.3f);
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
        // 清理下载进度管理器的资源
        if (downloadProgressManager != null) {
            downloadProgressManager.hideProgress();
        }
    }
    
    private void fetchCurrentSystemVersion() {
        backgroundExecutor.execute(() -> {
            // 使用标准化的系统版本号
            String systemBuildDate = DeviceInfoUtils.getSystemBuildDate();
            String standardSystemVersion = DeviceInfoUtils.getStandardSystemVersion();
            localSystemVersion = standardSystemVersion; // 使用标准化版本号
            
            mainThreadHandler.post(() -> {
                if (isAdded()) {
                    String versionToShow = latestOssVersion != null ? latestOssVersion : systemBuildDate;
                    tvCurrentVersion.setText(getString(R.string.current_version_label, versionToShow));
                }
            });
        });
    }
    
    private void checkSystemUpdate() {
        showStatus(getString(R.string.status_checking), false);
        canDownload = false;
        btnDownloadUpdate.setAlpha(0.3f);
        downloadClickCount = 0;
        String cpuModel = DeviceInfoUtils.getCpuModel();
        String resolution = DeviceInfoUtils.getScreenResolution(requireContext());
        OssManager ossManager = new OssManager(requireContext());
        ossManager.checkSystemUpdate(cpuModel, resolution, new OssManager.OssCallback<UpdateInfo>() {
            @Override
            public void onSuccess(@Nullable UpdateInfo result) {
                if (result != null) {
                    currentSystemUpdateInfo = result;
                    latestOssVersion = result.getVersion();
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvCurrentVersion.setText(getString(R.string.current_version_label, latestOssVersion));
                            tvLatestVersion.setText(getString(R.string.latest_version_label, result.getVersion()));
                            
                            // 获取本地标准化版本和OSS标准化版本
                            String localStandardVersion = localSystemVersion; // 已经是标准化版本
                            String ossStandardVersion = DeviceInfoUtils.formatDateToStandardVersion(latestOssVersion);
                            
                            // 直接比较标准化的版本号
                            try {
                                long localVersionNum = Long.parseLong(localStandardVersion);
                                long ossVersionNum = Long.parseLong(ossStandardVersion);
                                
                                Log.d("SystemUpdateFragment", "比较版本: 本地=" + localVersionNum + "(" + DeviceInfoUtils.getSystemBuildDate() + "), " +
                                      "OSS=" + ossVersionNum + "(" + latestOssVersion + ")");
                                
                                if (localVersionNum >= ossVersionNum) {
                                    showStatus(getString(R.string.status_up_to_date), false);
                                    canDownload = false;
                                    btnDownloadUpdate.setAlpha(0.3f);
                                } else {
                                    showStatus(getString(R.string.status_update_available), false);
                                    canDownload = true;
                                    btnDownloadUpdate.setAlpha(1.0f);
                                }
                            } catch (NumberFormatException e) {
                                // 极少数情况下可能会抛出异常，这里提供简单的回退策略
                                Log.e("SystemUpdateFragment", "版本比较异常", e);
                                if (localStandardVersion.equals(ossStandardVersion)) {
                                    showStatus(getString(R.string.status_up_to_date), false);
                                    canDownload = false;
                                    btnDownloadUpdate.setAlpha(0.3f);
                                } else {
                                    showStatus(getString(R.string.status_update_available), false);
                                    canDownload = true;
                                    btnDownloadUpdate.setAlpha(1.0f);
                                }
                            }
                        }
                    });
                } else {
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showStatus(getString(R.string.status_no_update), false);
                            canDownload = false;
                            btnDownloadUpdate.setAlpha(0.3f);
                        }
                    });
                }
            }
            
            @Override
            public void onFailure(@NonNull Exception e) {
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        showStatus(getString(R.string.status_error_check, e.getMessage()), true);
                        canDownload = false;
                        btnDownloadUpdate.setAlpha(0.3f);
                    }
                });
            }
        });
    }
    
    private void downloadUpdate() {
        if (currentSystemUpdateInfo == null) return;
        if (com.example.otaupdate.MainActivity.isDownloading()) {
            new android.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.download_in_progress_warning))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        com.example.otaupdate.MainActivity.setDownloading(true);
        isSystemDownloading = true;
        
        btnCheckUpdate.setEnabled(false);
        btnDownloadUpdate.setEnabled(false);
        
        // 显示下载进度UI
        if (downloadProgressManager != null) {
            downloadProgressManager.reset();
            downloadProgressManager.showProgress();
        }
        
        File downloadDir = new File(requireContext().getExternalFilesDir(null), "downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        
        // 设置下载文件路径
        String fileName = currentSystemUpdateInfo.getKey().substring(currentSystemUpdateInfo.getKey().lastIndexOf('/') + 1);
        downloadFile = new File(downloadDir, fileName);
        
        // 创建OssManager实例并开始下载
        OssManager ossManager = new OssManager(requireContext());
        ossManager.downloadUpdate(currentSystemUpdateInfo.getKey(), downloadFile.getAbsolutePath(), 
            new OssManager.DownloadCallback() {
                @Override
                public void onProgress(long currentSize, long totalSize) {
                    int progress = totalSize > 0 ? (int) ((currentSize * 100) / totalSize) : 0;
                    systemDownloadProgress = progress;
                    systemDownloadStatus = getString(R.string.status_downloading, progress);
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            // 使用下载进度管理器更新UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.updateProgress(
                                    currentSize, 
                                    totalSize, 
                                    getString(R.string.downloading_update, progress + "%")
                                );
                            }
                        }
                    });
                }
                
                @Override
                public void onSuccess() {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isSystemDownloading = false;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            // 隐藏下载进度UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.hideProgress();
                            }
                            
                            showStatus(getString(R.string.status_download_complete), false);
                            btnCheckUpdate.setEnabled(true);
                            showRebootDialog();
                        }
                    });
                }
                
                @Override
                public void onFailure(@NonNull Exception e) {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isSystemDownloading = false;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            // 隐藏下载进度UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.hideProgress();
                            }
                            
                            showStatus(getString(R.string.status_error_download, e.getMessage()), true);
                            btnCheckUpdate.setEnabled(true);
                            btnDownloadUpdate.setEnabled(true);
                        }
                    });
                }
            });
    }
    
    private void showStatus(String message, boolean isError) {
        if (tvUpdateStatus != null) {
            tvUpdateStatus.setVisibility(View.VISIBLE);
            tvUpdateStatus.setText(message);
            tvUpdateStatus.setTextColor(isError ? 
                    requireContext().getColor(android.R.color.holo_red_light) : 
                    requireContext().getColor(android.R.color.white));
        }
    }

    private void showRebootDialog() {
        if (!isAdded()) return;
        
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.reboot_dialog_title)
            .setMessage(R.string.reboot_dialog_message)
            .setPositiveButton(R.string.reboot_dialog_confirm, (dialog, which) -> {
                // Trigger reboot
                OssManager ossManager = new OssManager(requireContext());
                ossManager.rebootDevice();
            })
            .setNegativeButton(R.string.reboot_dialog_later, null)
            .setCancelable(false)
            .show();
    }
    
    // 提供外部访问静态变量的方法
    public static boolean isDownloading() {
        return isSystemDownloading;
    }
    
    public static int getDownloadProgress() {
        return systemDownloadProgress;
    }
    
    public static String getDownloadStatus() {
        return systemDownloadStatus;
    }

    /**
     * 初始化下载进度管理器
     */
    private void initDownloadProgressManager(View view) {
        downloadProgressManager = new DownloadProgressManager(requireContext(), view);
        downloadProgressManager.setCallback(new DownloadProgressManager.DownloadControlCallback() {
            @Override
            public void onPauseResumeClicked(boolean isPaused) {
                // 处理暂停/继续下载
                if (isPaused) {
                    // 暂停下载逻辑
                    OssManager ossManager = new OssManager(requireContext());
                    ossManager.pauseDownload();
                } else {
                    // 继续下载逻辑
                    if (currentSystemUpdateInfo != null && downloadFile != null) {
                        OssManager ossManager = new OssManager(requireContext());
                        ossManager.resumeDownload(currentSystemUpdateInfo.getKey(), downloadFile.getAbsolutePath());
                    }
                }
            }
            
            @Override
            public void onCancelClicked() {
                // 处理取消下载
                showCancelDownloadDialog();
            }
        });
    }
    
    /**
     * 显示取消下载确认对话框
     */
    private void showCancelDownloadDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_cancel_title)
            .setMessage(R.string.confirm_cancel_message)
            .setPositiveButton(R.string.btn_confirm, (dialog, which) -> {
                cancelDownload();
            })
            .setNegativeButton(R.string.btn_no, null)
            .show();
    }
    
    /**
     * 取消下载操作
     */
    private void cancelDownload() {
        if (isSystemDownloading) {
            OssManager ossManager = new OssManager(requireContext());
            ossManager.cancelDownload();
            
            isSystemDownloading = false;
            com.example.otaupdate.MainActivity.setDownloading(false);
            
            // 隐藏下载进度UI
            if (downloadProgressManager != null) {
                downloadProgressManager.hideProgress();
            }
            
            btnCheckUpdate.setEnabled(true);
            btnDownloadUpdate.setEnabled(true);
            showStatus(getString(R.string.status_download_cancelled), false);
            
            // 删除临时下载文件
            if (downloadFile != null && downloadFile.exists()) {
                downloadFile.delete();
            }
        }
    }
}