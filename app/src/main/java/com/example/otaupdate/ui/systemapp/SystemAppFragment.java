package com.example.otaupdate.ui.systemapp;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.cardview.widget.CardView;
import androidx.fragment.app.Fragment;

import com.example.otaupdate.BuildConfig;
import com.example.otaupdate.DeviceInfoUtils;
import com.example.otaupdate.OssManager;
import com.example.otaupdate.R;
import com.example.otaupdate.UpdateInfo;
import com.example.otaupdate.ui.views.RippleProgressView;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.example.otaupdate.DownloadProgressManager;

public class SystemAppFragment extends Fragment {

    private static final String TAG = "SystemAppFragment";
    
    private TextView tvCurrentSystemAppVersion;
    private TextView tvLatestSystemAppVersion;
    private TextView tvBuildTime;
    private TextView tvSystemAppStatus;
    private TextView tvProgressStatus;
    private Button btnCheckSystemAppUpdate;
    private Button btnDownloadSystemAppUpdate;
    private View progressContainer;
    private CircularProgressIndicator circularProgress;
    
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

    // 下载进度管理器
    private DownloadProgressManager downloadProgressManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_system_app, container, false);
        initViews(view);
        return view;
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
        
        // 初始化UI状态
        setupInitialUIState();
        
        // 获取应用编译时间，并只保留日期部分
        String appBuildTime = DeviceInfoUtils.getAppBuildTime();
        if (appBuildTime != null && appBuildTime.contains(" ")) {
            appBuildTime = appBuildTime.split(" ")[0]; // 只保留日期部分
        }
        
        // 显示编译时间
        tvBuildTime.setText(getString(R.string.system_app_version_label, appBuildTime));
        
        // 获取当前应用版本
        fetchCurrentAppVersion();
        
        // 恢复下载状态（如果有）
        if (isSystemAppDownloading) {
            btnCheckSystemAppUpdate.setEnabled(false);
            btnDownloadSystemAppUpdate.setEnabled(false);
        } else {
            progressContainer.setVisibility(View.GONE);
        }
        
        btnDownloadSystemAppUpdate.setAlpha(canDownload ? 1.0f : 0.3f);
        
        // 初始化下载进度管理器
        initDownloadProgressManager(view);
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
    
    private void initViews(View view) {
        tvCurrentSystemAppVersion = view.findViewById(R.id.tvCurrentSystemAppVersion);
        tvLatestSystemAppVersion = view.findViewById(R.id.tvLatestSystemAppVersion);
        tvBuildTime = view.findViewById(R.id.tvBuildTime);
        tvSystemAppStatus = view.findViewById(R.id.tvSystemAppStatus);
        tvProgressStatus = view.findViewById(R.id.tvProgressStatus);
        btnCheckSystemAppUpdate = view.findViewById(R.id.btnCheckSystemAppUpdate);
        btnDownloadSystemAppUpdate = view.findViewById(R.id.btnDownloadSystemAppUpdate);
        progressContainer = view.findViewById(R.id.progressContainer);
        circularProgress = view.findViewById(R.id.circularProgress);
    }
    
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
                    if (currentSystemAppUpdateInfo != null && downloadFile != null) {
                        OssManager ossManager = new OssManager(requireContext());
                        ossManager.resumeDownload(currentSystemAppUpdateInfo.getKey(), downloadFile.getAbsolutePath());
                    }
                }
            }
            
            @Override
            public void onCancelClicked() {
                // 处理取消下载
                showCancelDownloadDialog();
            }
        });
        
        // 如果正在下载，显示下载进度UI
        if (isSystemAppDownloading) {
            downloadProgressManager.showProgress();
            downloadProgressManager.updateProgress(
                systemAppDownloadProgress * 1024L, // 模拟字节数
                100 * 1024L,  // 模拟总字节数
                systemAppDownloadStatus
            );
        }
    }
    
    private void setupInitialUIState() {
        canDownload = false;
        btnDownloadSystemAppUpdate.setAlpha(0.3f);
        tvSystemAppStatus.setText("");
        tvCurrentSystemAppVersion.setText(getString(R.string.current_version_label_placeholder));
        tvLatestSystemAppVersion.setText(getString(R.string.latest_version_label_unknown));
        
        // 直接从布局中获取progressBar
        if (progressContainer != null) {
            ProgressBar progressBar = progressContainer.findViewById(R.id.progressBar);
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }
        }
    }
    
    private void fetchCurrentAppVersion() {
        backgroundExecutor.execute(() -> {
            String appBuildTime = DeviceInfoUtils.getAppBuildTime();
            // 获取标准化的应用版本号
            String standardAppVersion = DeviceInfoUtils.getStandardAppVersion();
            
            // 只保留日期部分，去掉时间
            String appBuildDate = appBuildTime;
            if (appBuildDate != null && appBuildDate.contains(" ")) {
                appBuildDate = appBuildDate.split(" ")[0]; // 只保留日期部分
            }
            
            // 只使用应用编译日期
            final String formattedVersion = appBuildDate;
            final String finalAppBuildDate = appBuildDate;
            localAppBuildTime = standardAppVersion; // 使用标准化版本号
            
            mainThreadHandler.post(() -> {
                if (isAdded()) {
                    tvCurrentSystemAppVersion.setText(getString(R.string.current_version_label, formattedVersion));
                    tvCurrentSystemAppVersion.setVisibility(View.VISIBLE); // 确保可见
                    
                    // 使用system_app_version_label替代build_time_label
                    tvBuildTime.setText(getString(R.string.system_app_version_label, finalAppBuildDate));
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
                            
                            // 使用本地标准化版本
                            String localStandardVersion = localAppBuildTime; // 已经是标准化版本
                            // 获取OSS上最新应用的标准化版本
                            String ossStandardVersion = DeviceInfoUtils.formatDateToStandardVersion(result.getVersion());
                            
                            // 打印日志，帮助调试版本比较
                            Log.d("SystemAppFragment", "比较版本: 本地=" + localStandardVersion + "(" + DeviceInfoUtils.getAppBuildTime() + "), OSS=" + ossStandardVersion + "(" + result.getVersion() + ")");
                            
                            // 直接比较标准化的版本号
                            try {
                                long localVersionNum = Long.parseLong(localStandardVersion);
                                long ossVersionNum = Long.parseLong(ossStandardVersion);
                                
                                if (localVersionNum >= ossVersionNum) {
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
                                // 如果解析失败，回退到字符串比较
                                Log.e("SystemAppFragment", "版本比较异常", e);
                                if (localStandardVersion.equals(ossStandardVersion)) {
                                    showStatus(getString(R.string.status_up_to_date), false);
                                    canDownload = false;
                                    btnDownloadSystemAppUpdate.setAlpha(0.3f);
                                } else {
                                    showStatus(getString(R.string.status_update_available), false);
                                    canDownload = true;
                                    btnDownloadSystemAppUpdate.setAlpha(1.0f);
                                }
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
        
        // 判断是否有可用更新
        if (canDownload) {
            // 如果有新版本可用，直接开始下载，不再显示确认对话框
            startDownload();
        } else {
            // 只有在强制更新时(当前已是最新版本)才显示确认对话框
            new AlertDialog.Builder(requireContext())
                .setTitle(R.string.confirm_update_title)
                .setMessage(R.string.confirm_update_message)
                .setPositiveButton(R.string.btn_update, (dialog, which) -> startDownload())
                .setNegativeButton(R.string.btn_ignore, null)
                .show();
        }
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
        
        // 显示下载进度UI
        if (downloadProgressManager != null) {
            downloadProgressManager.reset();
            downloadProgressManager.showProgress();
        }
        
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
                    isSystemAppDownloading = false;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            // 隐藏下载进度UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.hideProgress();
                            }
                            
                            btnCheckSystemAppUpdate.setEnabled(true);
                            // 下载完成后，显示安装动画
                            showInstallAnimation();
                            // 下载完成后，显示安装中状态
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
                            // 隐藏下载进度UI
                            if (downloadProgressManager != null) {
                                downloadProgressManager.hideProgress();
                            }
                            
                            btnCheckSystemAppUpdate.setEnabled(true);
                            btnDownloadSystemAppUpdate.setEnabled(true);
                            showStatus(getString(R.string.status_error_download, e.getMessage()), true);
                        }
                    });
                }
            });
    }
    
    /**
     * 显示安装动画效果
     */
    private void showInstallAnimation() {
        // 可以在这里添加安装过程的动画效果
        View rootView = getView();
        if (rootView != null) {
            Animation pulse = AnimationUtils.loadAnimation(requireContext(), R.anim.progress_pulse);
            tvSystemAppStatus.startAnimation(pulse);
        }
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
        // 从布局中查找progressBar
        ProgressBar progressBar = progressContainer.findViewById(R.id.progressBar);
        if (progress >= 0) {
            circularProgress.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            circularProgress.setProgress(progress);
            progressBar.setProgress(progress);
            progressBar.setIndeterminate(false);
            circularProgress.setIndeterminate(false);
        } else {
            circularProgress.setVisibility(View.VISIBLE);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setIndeterminate(true);
            circularProgress.setIndeterminate(true);
        }
        tvSystemAppStatus.setVisibility(View.GONE);
    }
    
    /**
     * 显示取消下载确认对话框
     */
    private void showCancelDownloadDialog() {
        new AlertDialog.Builder(requireContext())
            .setTitle(R.string.confirm_cancel_title)
            .setMessage(R.string.confirm_cancel_message)
            .setPositiveButton(R.string.btn_confirm, (dialog, which) -> cancelDownload())
            .setNegativeButton(R.string.btn_no, null)
            .show();
    }
    
    /**
     * 取消下载操作
     */
    private void cancelDownload() {
        if (isSystemAppDownloading) {
            OssManager ossManager = new OssManager(requireContext());
            ossManager.cancelDownload();
            
            isSystemAppDownloading = false;
            com.example.otaupdate.MainActivity.setDownloading(false);
            
            // 隐藏下载进度UI
            if (downloadProgressManager != null) {
                downloadProgressManager.hideProgress();
            }
            
            btnCheckSystemAppUpdate.setEnabled(true);
            btnDownloadSystemAppUpdate.setEnabled(true);
            showStatus(getString(R.string.status_download_cancelled), false);
            
            // 删除临时下载文件
            if (downloadFile != null && downloadFile.exists()) {
                downloadFile.delete();
            }
        }
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