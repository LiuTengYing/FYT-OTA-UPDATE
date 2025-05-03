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

public class McuUpdateFragment extends Fragment {

    private TextView tvCurrentMcu;
    private TextView tvMcuUpdateStatus;
    private TextView tvProgressStatus;
    private Button btnCheckMcuUpdate;
    private Button btnDownloadMcuUpdate;
    private Button btnCancelDownload;
    private ProgressBar pbMcuUpdate;
    private CircularProgressIndicator circularProgress;
    private RelativeLayout progressContainer;
    
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    private UpdateInfo currentMcuUpdateInfo = null;
    private File downloadFile = null;

    // 静态变量，用于在Fragment切换时保存下载状态
    private static boolean isMcuDownloading = false;
    private static int mcuDownloadProgress = 0;
    private static String mcuDownloadStatus = "";

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_mcu_update, container, false);
        
        tvCurrentMcu = view.findViewById(R.id.tvCurrentMcu);
        tvMcuUpdateStatus = view.findViewById(R.id.tvMcuUpdateStatus);
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus);
        btnCheckMcuUpdate = view.findViewById(R.id.btnCheckMcuUpdate);
        btnDownloadMcuUpdate = view.findViewById(R.id.btnDownloadMcuUpdate);
        btnCancelDownload = view.findViewById(R.id.btnCancelDownload);
        pbMcuUpdate = view.findViewById(R.id.pbMcuUpdate);
        circularProgress = view.findViewById(R.id.circularProgress);
        progressContainer = view.findViewById(R.id.progressContainer);
        
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        btnCheckMcuUpdate.setOnClickListener(v -> checkMcuUpdate());
        btnDownloadMcuUpdate.setOnClickListener(v -> downloadUpdate());
        btnCancelDownload.setOnClickListener(v -> showCancelDownloadDialog());
        
        fetchCurrentMcuVersion();
        
        // 恢复下载状态（如果有）
        if (isMcuDownloading) {
            btnCheckMcuUpdate.setEnabled(false);
            btnDownloadMcuUpdate.setEnabled(false);
            btnCancelDownload.setVisibility(View.VISIBLE);
            showProgress(mcuDownloadStatus, mcuDownloadProgress);
        } else {
            circularProgress.setVisibility(View.GONE);
            pbMcuUpdate.setVisibility(View.GONE);
            btnCancelDownload.setVisibility(View.GONE);
        }
        
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
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
        btnCancelDownload.setVisibility(View.VISIBLE);
        progressContainer.setVisibility(View.VISIBLE);
        
        File downloadDir = new File(requireContext().getExternalFilesDir(null), "downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        
        downloadFile = new File(downloadDir, "mcu_update.zip");
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
                            showProgress(mcuDownloadStatus, progress);
                        }
                    });
                }
                
                @Override
                public void onSuccess() {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isMcuDownloading = false;
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
                    isMcuDownloading = false;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            progressContainer.setVisibility(View.GONE);
                            btnCancelDownload.setVisibility(View.GONE);
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
        isMcuDownloading = false;
        mcuDownloadProgress = 0;
        
        // 更新UI
        btnCheckMcuUpdate.setEnabled(true);
        btnDownloadMcuUpdate.setEnabled(true);
        btnCancelDownload.setVisibility(View.GONE);
        progressContainer.setVisibility(View.GONE);
        
        showStatus(getString(R.string.download_cancelled), false);
    }
    
    private void showProgress(String message, int progress) {
        progressContainer.setVisibility(View.VISIBLE);
        tvProgressStatus.setVisibility(View.VISIBLE);
        tvProgressStatus.setText(message);
        if (progress >= 0) {
            circularProgress.setVisibility(View.VISIBLE);
            pbMcuUpdate.setVisibility(View.VISIBLE);
            circularProgress.setProgress(progress);
            pbMcuUpdate.setProgress(progress);
            pbMcuUpdate.setIndeterminate(false);
            circularProgress.setIndeterminate(false);
        } else {
            circularProgress.setVisibility(View.VISIBLE);
            pbMcuUpdate.setVisibility(View.VISIBLE);
            pbMcuUpdate.setIndeterminate(true);
            circularProgress.setIndeterminate(true);
        }
        tvMcuUpdateStatus.setVisibility(View.GONE);
    }

    private void showStatus(String message, boolean isError) {
        tvMcuUpdateStatus.setVisibility(View.VISIBLE);
        tvMcuUpdateStatus.setText(message);
        tvMcuUpdateStatus.setTextColor(getResources().getColor(
            isError ? android.R.color.holo_red_light : android.R.color.white));
        progressContainer.setVisibility(View.GONE);
        circularProgress.setVisibility(View.GONE);
        pbMcuUpdate.setVisibility(View.GONE);
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