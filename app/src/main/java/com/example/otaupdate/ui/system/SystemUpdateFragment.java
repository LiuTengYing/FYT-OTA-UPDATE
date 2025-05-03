package com.example.otaupdate.ui.system;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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
    private TextView tvProgressStatus;
    private Button btnCheckUpdate;
    private Button btnDownloadUpdate;
    private Button btnCancelDownload;
    private RelativeLayout progressContainer;
    private CircularProgressIndicator circularProgress;
    private ProgressBar pbSystemUpdate;
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

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_system_update, container, false);
        
        tvCurrentVersion = view.findViewById(R.id.tvCurrentVersion);
        tvLatestVersion = view.findViewById(R.id.tvLatestVersion);
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus);
        btnCheckUpdate = view.findViewById(R.id.btnCheckUpdate);
        btnDownloadUpdate = view.findViewById(R.id.btnDownloadUpdate);
        btnCancelDownload = view.findViewById(R.id.btnCancelDownload);
        progressContainer = view.findViewById(R.id.progressContainer);
        circularProgress = view.findViewById(R.id.circularProgress);
        pbSystemUpdate = view.findViewById(R.id.pbSystemUpdate);
        tvUpdateStatus = view.findViewById(R.id.tvUpdateStatus);
        
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
        
        btnCancelDownload.setOnClickListener(v -> {
            showCancelDownloadDialog();
        });
        
        fetchCurrentSystemVersion();
        
        // 恢复下载状态（如果有）
        if (isSystemDownloading) {
            btnCheckUpdate.setEnabled(false);
            btnDownloadUpdate.setEnabled(false);
            btnCancelDownload.setVisibility(View.VISIBLE);
            showProgress(systemDownloadStatus, systemDownloadProgress);
        } else {
            circularProgress.setVisibility(View.GONE);
            pbSystemUpdate.setVisibility(View.GONE);
            btnCancelDownload.setVisibility(View.GONE);
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
    }
    
    private void fetchCurrentSystemVersion() {
        backgroundExecutor.execute(() -> {
            String systemBuildDate = DeviceInfoUtils.getSystemBuildDate();
            localSystemVersion = systemBuildDate;
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
                            String localDate = localSystemVersion;
                            String ossDate = latestOssVersion;
                            if (localDate != null && ossDate != null && localDate.replaceAll("[^0-9]", "").contains(ossDate)) {
                                showStatus(getString(R.string.status_up_to_date), false);
                                canDownload = false;
                                btnDownloadUpdate.setAlpha(0.3f);
                            } else {
                                showStatus(getString(R.string.status_update_available), false);
                                canDownload = true;
                                btnDownloadUpdate.setAlpha(1.0f);
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
        btnCancelDownload.setVisibility(View.VISIBLE);
        progressContainer.setVisibility(View.VISIBLE);
        
        File downloadDir = new File(requireContext().getExternalFilesDir(null), "downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        
        downloadFile = new File(downloadDir, "system_update.zip");
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
                            showProgress(systemDownloadStatus, progress);
                        }
                    });
                }
                
                @Override
                public void onSuccess() {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isSystemDownloading = false;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showProgress(getString(R.string.status_download_complete), 100);
                            showProgress(getString(R.string.status_extracting), -1);
                            btnCancelDownload.setVisibility(View.GONE);
                            
                            // 延迟一段时间后显示移动文件状态
                            mainThreadHandler.postDelayed(() -> {
                                if (isAdded()) {
                                    showProgress(getString(R.string.status_moving_files), -1);
                                    
                                    // 再延迟一段时间后显示完成状态并弹出重启对话框
                                    mainThreadHandler.postDelayed(() -> {
                                        if (isAdded()) {
                                            progressContainer.setVisibility(View.GONE);
                                            showStatus(getString(R.string.status_update_ready), false);
                                            showRebootDialog();
                                        }
                                    }, 2000);
                                }
                            }, 2000);
                        }
                    });
                }
                
                @Override
                public void onFailure(@NonNull Exception e) {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isSystemDownloading = false;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            progressContainer.setVisibility(View.GONE);
                            btnCancelDownload.setVisibility(View.GONE);
                            showStatus(getString(R.string.status_error_download, e.getMessage()), true);
                            btnCheckUpdate.setEnabled(true);
                            btnDownloadUpdate.setEnabled(true);
                        }
                    });
                }
            });
    }
    
    private void showProgress(String message, int progress) {
        progressContainer.setVisibility(View.VISIBLE);
        tvProgressStatus.setVisibility(View.VISIBLE);
        tvProgressStatus.setText(message);
        if (progress >= 0) {
            circularProgress.setVisibility(View.VISIBLE);
            pbSystemUpdate.setVisibility(View.VISIBLE);
            circularProgress.setProgress(progress);
            pbSystemUpdate.setProgress(progress);
            pbSystemUpdate.setIndeterminate(false);
            circularProgress.setIndeterminate(false);
        } else {
            circularProgress.setVisibility(View.VISIBLE);
            pbSystemUpdate.setVisibility(View.VISIBLE);
            pbSystemUpdate.setIndeterminate(true);
            circularProgress.setIndeterminate(true);
        }
        tvUpdateStatus.setVisibility(View.GONE);
    }

    private void showStatus(String message, boolean isError) {
        tvUpdateStatus.setVisibility(View.VISIBLE);
        tvUpdateStatus.setText(message);
        tvUpdateStatus.setTextColor(getResources().getColor(
            isError ? android.R.color.holo_red_light : android.R.color.white));
        progressContainer.setVisibility(View.GONE);
        circularProgress.setVisibility(View.GONE);
        pbSystemUpdate.setVisibility(View.GONE);
    }

    private void showCancelDownloadDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_cancel_download_title)
            .setMessage(R.string.confirm_cancel_download_message)
            .setPositiveButton(R.string.ok, (dialog, which) -> {
                cancelDownload();
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }
    
    private void cancelDownload() {
        // 取消下载任务
        OssManager ossManager = new OssManager(requireContext());
        ossManager.cancelDownload();
        
        // 删除已下载的文件
        if (downloadFile != null && downloadFile.exists()) {
            downloadFile.delete();
        }
        
        // 重置状态
        com.example.otaupdate.MainActivity.setDownloading(false);
        isSystemDownloading = false;
        systemDownloadProgress = 0;
        
        // 更新UI
        btnCheckUpdate.setEnabled(true);
        btnDownloadUpdate.setEnabled(true);
        btnCancelDownload.setVisibility(View.GONE);
        progressContainer.setVisibility(View.GONE);
        
        showStatus(getString(R.string.download_cancelled), false);
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
}