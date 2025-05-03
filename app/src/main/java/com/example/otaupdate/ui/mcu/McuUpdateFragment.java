package com.example.otaupdate.ui.mcu;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.otaupdate.DeviceInfoUtils;
import com.example.otaupdate.DownloadProgressManager;
import com.example.otaupdate.R;
import com.example.otaupdate.UpdateInfo;
import com.example.otaupdate.OssManager;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class McuUpdateFragment extends Fragment {

    private TextView tvCurrentMcu;
    private TextView tvLatestMcuVersion;
    private TextView tvMcuUpdateStatus;
    private Button btnCheckMcuUpdate;
    private Button btnDownloadMcuUpdate;
    private View progressContainer;
    
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    private UpdateInfo currentMcuUpdateInfo = null;
    private File downloadFile = null;

    // 静态变量，用于在Fragment切换时保存下载状态
    private static boolean isMcuDownloading = false;
    private static int mcuDownloadProgress = 0;
    private static String mcuDownloadStatus = "";
    
    // 下载进度管理器
    private DownloadProgressManager downloadProgressManager;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mcu_update, container, false);
        
        tvCurrentMcu = view.findViewById(R.id.tvCurrentMcu);
        tvLatestMcuVersion = view.findViewById(R.id.tvLatestMcuVersion);
        tvMcuUpdateStatus = view.findViewById(R.id.tvMcuUpdateStatus);
        btnCheckMcuUpdate = view.findViewById(R.id.btnCheckMcuUpdate);
        btnDownloadMcuUpdate = view.findViewById(R.id.btnDownloadMcuUpdate);
        progressContainer = view.findViewById(R.id.progressContainer);
        
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        btnCheckMcuUpdate.setOnClickListener(v -> checkMcuUpdate());
        btnDownloadMcuUpdate.setOnClickListener(v -> downloadUpdate());
        
        // 初始化下载进度管理器
        initDownloadProgressManager(view);
        
        fetchCurrentMcuVersion();
        
        // 恢复下载状态（如果有）
        if (isMcuDownloading) {
            btnCheckMcuUpdate.setEnabled(false);
            btnDownloadMcuUpdate.setEnabled(false);
            
            // 使用下载进度管理器显示进度
            if (downloadProgressManager != null) {
                downloadProgressManager.showProgress();
                downloadProgressManager.updateProgress(
                    mcuDownloadProgress * 1024L, // 模拟字节数
                    100 * 1024L, // 模拟总字节数
                    mcuDownloadStatus
                );
            }
        } else {
            if (progressContainer != null) {
                progressContainer.setVisibility(View.GONE);
            }
        }
        
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
                    if (currentMcuUpdateInfo != null && downloadFile != null) {
                        OssManager ossManager = new OssManager(requireContext());
                        ossManager.resumeDownload(currentMcuUpdateInfo.getKey(), downloadFile.getAbsolutePath());
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
    
    private void fetchCurrentMcuVersion() {
        backgroundExecutor.execute(() -> {
            String mcuVersion = DeviceInfoUtils.getMcuVersion();
            mainThreadHandler.post(() -> {
                if (isAdded()) {
                    tvCurrentMcu.setText(getString(R.string.current_mcu_label, mcuVersion));
                }
            });
        });
    }
    
    private void checkMcuUpdate() {
        showStatus(getString(R.string.status_checking), false);
        btnDownloadMcuUpdate.setEnabled(false);
        
        String mcuVersion = DeviceInfoUtils.getMcuVersion();
        
        OssManager ossManager = new OssManager(requireContext());
        ossManager.checkMcuUpdate(mcuVersion, new OssManager.OssCallback<UpdateInfo>() {
            @Override
            public void onSuccess(@Nullable UpdateInfo result) {
                if (result != null) {
                    currentMcuUpdateInfo = result;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvLatestMcuVersion.setText(getString(R.string.latest_version_label, result.getVersion()));
                            showStatus(getString(R.string.status_mcu_update_available, result.getVersion()), false);
                            btnDownloadMcuUpdate.setEnabled(true);
                        }
                    });
                } else {
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showStatus(getString(R.string.status_no_mcu_update), false);
                            btnDownloadMcuUpdate.setEnabled(false);
                        }
                    });
                }
            }
            
            @Override
            public void onFailure(@NonNull Exception e) {
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        showStatus(getString(R.string.status_error_check, e.getMessage()), true);
                        btnDownloadMcuUpdate.setEnabled(false);
                    }
                });
            }
        });
    }
    
    private void downloadUpdate() {
        if (currentMcuUpdateInfo == null) return;
        if (com.example.otaupdate.MainActivity.isDownloading()) {
            new android.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.download_in_progress_warning))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        com.example.otaupdate.MainActivity.setDownloading(true);
        isMcuDownloading = true;
        
        btnCheckMcuUpdate.setEnabled(false);
        btnDownloadMcuUpdate.setEnabled(false);
        
        // 显示下载进度UI
        if (downloadProgressManager != null) {
            downloadProgressManager.reset();
            downloadProgressManager.showProgress();
        }
        
        File downloadDir = new File(requireContext().getExternalFilesDir(null), "downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        
        String fileName = currentMcuUpdateInfo.getKey().substring(currentMcuUpdateInfo.getKey().lastIndexOf('/') + 1);
        downloadFile = new File(downloadDir, fileName);
        OssManager ossManager = new OssManager(requireContext());
        
        ossManager.downloadUpdate(currentMcuUpdateInfo.getKey(), downloadFile.getAbsolutePath(), 
            new OssManager.DownloadCallback() {
                @Override
                public void onProgress(long currentSize, long totalSize) {
                    int progress = totalSize > 0 ? (int) ((currentSize * 100) / totalSize) : 0;
                    mcuDownloadProgress = progress;
                    mcuDownloadStatus = getString(R.string.status_downloading, progress);
                    
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
                    isMcuDownloading = false;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            // 隐藏下载进度UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.hideProgress();
                            }
                            
                            showStatus(getString(R.string.status_download_complete), false);
                            btnCheckMcuUpdate.setEnabled(true);
                            showRebootDialog();
                        }
                    });
                }
                
                @Override
                public void onFailure(@NonNull Exception e) {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isMcuDownloading = false;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            // 隐藏下载进度UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.hideProgress();
                            }
                            
                            showStatus(getString(R.string.status_error_download, e.getMessage()), true);
                            btnCheckMcuUpdate.setEnabled(true);
                            btnDownloadMcuUpdate.setEnabled(true);
                        }
                    });
                }
            });
    }
    
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
    
    private void cancelDownload() {
        if (isMcuDownloading) {
            // 取消下载任务
            OssManager ossManager = new OssManager(requireContext());
            ossManager.cancelDownload();
            
            isMcuDownloading = false;
            com.example.otaupdate.MainActivity.setDownloading(false);
            
            // 隐藏下载进度UI
            if (downloadProgressManager != null) {
                downloadProgressManager.hideProgress();
            }
            
            btnCheckMcuUpdate.setEnabled(true);
            btnDownloadMcuUpdate.setEnabled(true);
            showStatus(getString(R.string.status_download_cancelled), false);
            
            // 删除临时下载文件
            if (downloadFile != null && downloadFile.exists()) {
                downloadFile.delete();
            }
        }
    }
    
    private void showStatus(String message, boolean isError) {
        if (tvMcuUpdateStatus != null) {
            tvMcuUpdateStatus.setVisibility(View.VISIBLE);
            tvMcuUpdateStatus.setText(message);
            tvMcuUpdateStatus.setTextColor(isError ? 
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
        return isMcuDownloading;
    }
    
    public static int getDownloadProgress() {
        return mcuDownloadProgress;
    }
    
    public static String getDownloadStatus() {
        return mcuDownloadStatus;
    }
}