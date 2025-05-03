package com.example.otaupdate.ui.systemapp;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.otaupdate.BuildConfig;
import com.example.otaupdate.DeviceInfoUtils;
import com.example.otaupdate.OssManager;
import com.example.otaupdate.R;
import com.example.otaupdate.UpdateInfo;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SystemAppFragment extends Fragment {

    private TextView tvCurrentSystemAppVersion;
    private TextView tvLatestSystemAppVersion;
    private TextView tvBuildTime;
    private TextView tvSystemAppStatus;
    private TextView tvProgressStatus;
    private Button btnCheckSystemAppUpdate;
    private Button btnDownloadSystemAppUpdate;
    private Button btnCancelDownload;
    private ProgressBar pbSystemAppUpdate;
    private CircularProgressIndicator circularProgress;
    private RelativeLayout progressContainer;
    
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;
    private UpdateInfo currentSystemAppUpdateInfo = null;
    private int downloadClickCount = 0;
    private long lastDownloadClickTime = 0;
    private static final int FORCE_DOWNLOAD_CLICKS = 10;
    private String localAppBuildTime = null;
    private boolean canDownload = false;
    private File downloadFile = null;

    // 静态变量，用于在Fragment切换时保存下载状态
    private static boolean isSystemAppDownloading = false;
    private static int systemAppDownloadProgress = 0;
    private static String systemAppDownloadStatus = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_system_app, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        
        // 初始化视图
        tvCurrentSystemAppVersion = view.findViewById(R.id.tvCurrentSystemAppVersion);
        tvLatestSystemAppVersion = view.findViewById(R.id.tvLatestSystemAppVersion);
        tvBuildTime = view.findViewById(R.id.tvBuildTime);
        tvSystemAppStatus = view.findViewById(R.id.tvSystemAppStatus);
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus);
        btnCheckSystemAppUpdate = view.findViewById(R.id.btnCheckSystemAppUpdate);
        btnDownloadSystemAppUpdate = view.findViewById(R.id.btnDownloadSystemAppUpdate);
        btnCancelDownload = view.findViewById(R.id.btnCancelDownload);
        pbSystemAppUpdate = view.findViewById(R.id.pbSystemAppUpdate);
        circularProgress = view.findViewById(R.id.circularProgress);
        progressContainer = view.findViewById(R.id.progressContainer);
        
        // 初始化线程处理
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        // 设置按钮点击事件
        btnCheckSystemAppUpdate.setOnClickListener(v -> checkSystemAppUpdate());
        btnDownloadSystemAppUpdate.setOnClickListener(v -> {
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
                            btnDownloadSystemAppUpdate.setAlpha(1.0f);
                            showStatus(getString(R.string.status_force_update_unlocked), false);
                        })
                        .setNegativeButton(R.string.force_update_cancel, null)
                        .show();
                    downloadClickCount = 0;
                }
                return;
            }
            downloadSystemAppUpdate();
        });
        
        btnCancelDownload.setOnClickListener(v -> {
            showCancelDownloadDialog();
        });
        
        // 初始化UI状态
        setupInitialUIState();
        
        // 获取应用编译时间
        String appBuildTime = DeviceInfoUtils.getAppBuildTime();
        
        // 显示编译时间
        tvBuildTime.setText(getString(R.string.build_time_label, appBuildTime));
        
        // 获取当前应用版本
        fetchCurrentAppVersion();
        
        // 恢复下载状态（如果有）
        if (isSystemAppDownloading) {
            btnCheckSystemAppUpdate.setEnabled(false);
            btnDownloadSystemAppUpdate.setEnabled(false);
            btnCancelDownload.setVisibility(View.VISIBLE);
            showProgress(systemAppDownloadStatus, systemAppDownloadProgress);
        } else {
            progressContainer.setVisibility(View.GONE);
            circularProgress.setVisibility(View.GONE);
            pbSystemAppUpdate.setVisibility(View.GONE);
            btnCancelDownload.setVisibility(View.GONE);
        }
        
        btnDownloadSystemAppUpdate.setAlpha(canDownload ? 1.0f : 0.3f);
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
    }
    
    private void setupInitialUIState() {
        canDownload = false;
        btnDownloadSystemAppUpdate.setAlpha(0.3f);
        tvSystemAppStatus.setText("");
        tvCurrentSystemAppVersion.setText(getString(R.string.current_version_label_placeholder));
        tvLatestSystemAppVersion.setText(getString(R.string.latest_version_label_unknown));
        pbSystemAppUpdate.setVisibility(View.GONE);
    }
    
    private void fetchCurrentAppVersion() {
        backgroundExecutor.execute(() -> {
            String appBuildTime = DeviceInfoUtils.getAppBuildTime();
            String cpuModel = DeviceInfoUtils.getCpuModel();
            String resolution = DeviceInfoUtils.getScreenResolution(requireContext());
            
            // 格式化分辨率，确保宽度和高度按规则排序
            if (resolution != null && resolution.contains("x")) {
                String[] parts = resolution.split("x");
                if (parts.length == 2) {
                    try {
                        int width = Integer.parseInt(parts[0]);
                        int height = Integer.parseInt(parts[1]);
                        if (width < height) {
                            resolution = height + "x" + width;
                        }
                    } catch (NumberFormatException e) {
                        // 忽略解析错误，使用原始分辨率
                    }
                }
            }
            
            final String formattedVersion = cpuModel + "_" + resolution + "_" + appBuildTime;
            localAppBuildTime = appBuildTime;
            
            mainThreadHandler.post(() -> {
                if (isAdded()) {
                    tvCurrentSystemAppVersion.setText(getString(R.string.current_version_label, formattedVersion));
                    tvCurrentSystemAppVersion.setVisibility(View.VISIBLE); // 确保可见
                }
            });
        });
    }
    
    private void checkSystemAppUpdate() {
        showStatus(getString(R.string.status_checking), false);
        canDownload = false;
        btnDownloadSystemAppUpdate.setAlpha(0.3f);
        downloadClickCount = 0;
        OssManager ossManager = new OssManager(requireContext());
        ossManager.checkSystemAppUpdate(new OssManager.OssCallback<UpdateInfo>() {
            @Override
            public void onSuccess(@Nullable UpdateInfo result) {
                if (result != null) {
                    currentSystemAppUpdateInfo = result;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvLatestSystemAppVersion.setText(getString(R.string.latest_version_label, result.getVersion()));
                            
                            // 获取本地应用的构建日期（从localAppBuildTime中提取）
                            String localDate = localAppBuildTime != null ? localAppBuildTime.replaceAll("[^0-9]", "") : null;
                            // 获取OSS上最新应用的构建日期
                            String ossDate = result.getVersion();
                            
                            // 打印日志，帮助调试版本比较
                            Log.d("SystemAppFragment", "本地构建日期: " + localDate + ", OSS构建日期: " + ossDate);
                            
                            if (localDate != null && ossDate != null) {
                                // 尝试将日期转换为数字进行比较
                                try {
                                    long localDateNum = Long.parseLong(localDate.replaceAll("[^0-9]", ""));
                                    long ossDateNum = Long.parseLong(ossDate.replaceAll("[^0-9]", ""));
                                    
                                    if (localDateNum >= ossDateNum) {
                                        // 本地版本比OSS版本新或相同，不需要更新
                                        showStatus(getString(R.string.status_up_to_date), false);
                                        canDownload = false;
                                        btnDownloadSystemAppUpdate.setAlpha(0.3f);
                                    } else {
                                        // OSS版本比本地版本新，可以更新
                                        showStatus(getString(R.string.status_update_available), false);
                                        canDownload = true;
                                        btnDownloadSystemAppUpdate.setAlpha(1.0f);
                                    }
                                } catch (NumberFormatException e) {
                                    // 如果解析失败，回退到字符串包含检查
                                    if (localDate.contains(ossDate)) {
                                        showStatus(getString(R.string.status_up_to_date), false);
                                        canDownload = false;
                                        btnDownloadSystemAppUpdate.setAlpha(0.3f);
                                    } else {
                                        showStatus(getString(R.string.status_update_available), false);
                                        canDownload = true;
                                        btnDownloadSystemAppUpdate.setAlpha(1.0f);
                                    }
                                }
                            } else {
                                // 如果无法获取日期信息，则允许更新
                                showStatus(getString(R.string.status_update_available), false);
                                canDownload = true;
                                btnDownloadSystemAppUpdate.setAlpha(1.0f);
                            }
                        }
                    });
                } else {
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showStatus(getString(R.string.status_no_update), false);
                            canDownload = false;
                            btnDownloadSystemAppUpdate.setAlpha(0.3f);
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
                        btnDownloadSystemAppUpdate.setAlpha(0.3f);
                    }
                });
            }
        });
    }
    
    private void downloadSystemAppUpdate() {
        if (currentSystemAppUpdateInfo == null) {
            return;
        }
        
        // 显示确认对话框
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_update_title)
            .setMessage(R.string.confirm_update_message)
            .setPositiveButton(R.string.btn_update, (dialog, which) -> startDownload())
            .setNegativeButton(R.string.btn_ignore, null)
            .show();
    }
    
    private void startDownload() {
        if (!btnDownloadSystemAppUpdate.isEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastDownloadClickTime < 2000) {
                downloadClickCount++;
            } else {
                downloadClickCount = 1;
            }
            lastDownloadClickTime = now;
            if (downloadClickCount >= FORCE_DOWNLOAD_CLICKS) {
                btnDownloadSystemAppUpdate.setEnabled(true);
                showStatus(getString(R.string.status_update_available), false);
            }
            return;
        }
        if (com.example.otaupdate.MainActivity.isDownloading()) {
            new android.app.AlertDialog.Builder(requireContext())
                .setMessage(getString(R.string.download_in_progress_warning))
                .setPositiveButton(android.R.string.ok, null)
                .show();
            return;
        }
        com.example.otaupdate.MainActivity.setDownloading(true);
        isSystemAppDownloading = true;
        
        showStatus(getString(R.string.status_downloading, 0), false);
        progressContainer.setVisibility(View.VISIBLE);
        btnCancelDownload.setVisibility(View.VISIBLE);
        btnDownloadSystemAppUpdate.setEnabled(false);
        btnCheckSystemAppUpdate.setEnabled(false);
        
        // 创建下载目录
        File downloadDir = new File(requireContext().getExternalFilesDir(null), "downloads");
        if (!downloadDir.exists()) {
            downloadDir.mkdirs();
        }
        
        // 设置下载文件路径
        String fileName = currentSystemAppUpdateInfo.getKey().substring(currentSystemAppUpdateInfo.getKey().lastIndexOf('/') + 1);
        downloadFile = new File(downloadDir, fileName);
        
        // 创建OssManager实例并开始下载
        OssManager ossManager = new OssManager(requireContext());
        ossManager.downloadUpdate(currentSystemAppUpdateInfo.getKey(), downloadFile.getAbsolutePath(), 
            new OssManager.DownloadCallback() {
                @Override
                public void onProgress(long currentSize, long totalSize) {
                    int progress = totalSize > 0 ? (int) ((currentSize * 100) / totalSize) : 0;
                    systemAppDownloadProgress = progress;
                    systemAppDownloadStatus = getString(R.string.status_downloading, progress);
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showProgress(systemAppDownloadStatus, progress);
                        }
                    });
                }
                
                @Override
                public void onSuccess() {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isSystemAppDownloading = false;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showStatus(getString(R.string.status_download_complete), false);
                            progressContainer.setVisibility(View.GONE);
                            btnCancelDownload.setVisibility(View.GONE);
                            btnCheckSystemAppUpdate.setEnabled(true);
                            // 下载完成后，OssManager会自动处理解压、文件移动和系统重启
                            showStatus(getString(R.string.status_installing), false);
                        }
                    });
                }
                
                @Override
                public void onFailure(@NonNull Exception e) {
                    com.example.otaupdate.MainActivity.setDownloading(false);
                    isSystemAppDownloading = false;
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            showStatus(getString(R.string.status_error_download, e.getMessage()), true);
                            progressContainer.setVisibility(View.GONE);
                            btnCancelDownload.setVisibility(View.GONE);
                            btnCheckSystemAppUpdate.setEnabled(true);
                            btnDownloadSystemAppUpdate.setEnabled(true);
                        }
                    });
                }
            });
    }
    
    private void showStatus(String message, boolean isError) {
        if (tvSystemAppStatus != null) {
            tvSystemAppStatus.setVisibility(View.VISIBLE);
            tvSystemAppStatus.setText(message);
            tvSystemAppStatus.setTextColor(isError ? 
                    requireContext().getColor(android.R.color.holo_red_light) : 
                    requireContext().getColor(android.R.color.white));
            tvSystemAppStatus.setBackgroundResource(0);
            tvSystemAppStatus.setPadding(0, 0, 0, 0);
            progressContainer.setVisibility(View.GONE);
        }
    }

    private void showProgress(String message, int progress) {
        progressContainer.setVisibility(View.VISIBLE);
        tvProgressStatus.setVisibility(View.VISIBLE);
        tvProgressStatus.setText(message);
        if (progress >= 0) {
            circularProgress.setVisibility(View.VISIBLE);
            pbSystemAppUpdate.setVisibility(View.VISIBLE);
            circularProgress.setProgress(progress);
            pbSystemAppUpdate.setProgress(progress);
            pbSystemAppUpdate.setIndeterminate(false);
            circularProgress.setIndeterminate(false);
        } else {
            circularProgress.setVisibility(View.VISIBLE);
            pbSystemAppUpdate.setVisibility(View.VISIBLE);
            pbSystemAppUpdate.setIndeterminate(true);
            circularProgress.setIndeterminate(true);
        }
        tvSystemAppStatus.setVisibility(View.GONE);
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
        isSystemAppDownloading = false;
        systemAppDownloadProgress = 0;
        
        // 更新UI
        btnCheckSystemAppUpdate.setEnabled(true);
        btnDownloadSystemAppUpdate.setEnabled(true);
        btnCancelDownload.setVisibility(View.GONE);
        progressContainer.setVisibility(View.GONE);
        
        showStatus(getString(R.string.download_cancelled), false);
    }
    
    // 提供外部访问静态变量的方法
    public static boolean isDownloading() {
        return isSystemAppDownloading;
    }
    
    public static int getDownloadProgress() {
        return systemAppDownloadProgress;
    }
    
    public static String getDownloadStatus() {
        return systemAppDownloadStatus;
    }
}