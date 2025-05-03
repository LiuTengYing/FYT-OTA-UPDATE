package com.example.otaupdate;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.example.otaupdate.ui.views.RippleProgressView;
import com.google.android.material.progressindicator.CircularProgressIndicator;

import java.text.DecimalFormat;
import java.util.Locale;

/**
 * 下载进度管理器，负责更新下载进度UI和计算下载速度
 */
public class DownloadProgressManager {
    private static final int UPDATE_INTERVAL = 1000; // 更新间隔（毫秒）
    
    private final Context context;
    private final Handler handler;
    private final View progressContainer;
    private final CircularProgressIndicator circularProgress;
    private final RippleProgressView rippleProgressView;
    private final ProgressBar progressBar;
    private final TextView tvProgressStatus;
    private final TextView tvDownloadInfo;
    private final Button btnPauseResume;
    private final Button btnCancel;
    
    private ValueAnimator rippleAnimator;
    private long lastUpdateTime = 0;
    private long lastDownloadedBytes = 0;
    private float downloadSpeed = 0; // 字节/秒
    private boolean isPaused = false;
    
    /**
     * 下载控制回调接口
     */
    public interface DownloadControlCallback {
        void onPauseResumeClicked(boolean isPaused);
        void onCancelClicked();
    }
    
    private DownloadControlCallback callback;
    
    public DownloadProgressManager(Context context, View rootView) {
        this.context = context;
        this.handler = new Handler(Looper.getMainLooper());
        
        // 初始化视图
        this.progressContainer = rootView.findViewById(R.id.progressContainer);
        this.circularProgress = rootView.findViewById(R.id.circularProgress);
        this.rippleProgressView = rootView.findViewById(R.id.rippleProgressView);
        this.progressBar = rootView.findViewById(R.id.progressBar);
        this.tvProgressStatus = rootView.findViewById(R.id.tvProgressStatus);
        this.tvDownloadInfo = rootView.findViewById(R.id.tvDownloadInfo);
        this.btnPauseResume = rootView.findViewById(R.id.btnPauseResume);
        this.btnCancel = rootView.findViewById(R.id.btnCancel);
        
        // 设置按钮点击事件
        if (btnPauseResume != null) {
            btnPauseResume.setOnClickListener(v -> {
                isPaused = !isPaused;
                updatePauseResumeButton();
                if (callback != null) {
                    callback.onPauseResumeClicked(isPaused);
                }
            });
        }
        
        if (btnCancel != null) {
            btnCancel.setOnClickListener(v -> {
                if (callback != null) {
                    callback.onCancelClicked();
                }
            });
        }
    }
    
    /**
     * 设置下载控制回调
     */
    public void setCallback(DownloadControlCallback callback) {
        this.callback = callback;
    }
    
    /**
     * 显示下载进度UI
     */
    public void showProgress() {
        if (progressContainer != null) {
            progressContainer.setVisibility(View.VISIBLE);
        }
        
        // 启动波纹动画
        startRippleAnimation();
    }
    
    /**
     * 隐藏下载进度UI
     */
    public void hideProgress() {
        if (progressContainer != null) {
            progressContainer.setVisibility(View.GONE);
        }
        
        // 停止波纹动画
        stopRippleAnimation();
    }
    
    /**
     * 重置下载状态
     */
    public void reset() {
        isPaused = false;
        lastUpdateTime = 0;
        lastDownloadedBytes = 0;
        downloadSpeed = 0;
        
        if (progressBar != null) {
            progressBar.setProgress(0);
        }
        
        if (circularProgress != null) {
            circularProgress.setProgress(0);
        }
        
        if (rippleProgressView != null) {
            rippleProgressView.setProgress(0);
        }
        
        updatePauseResumeButton();
    }
    
    /**
     * 更新下载进度
     * @param currentSize 当前已下载字节数
     * @param totalSize 总字节数
     * @param status 状态文本
     */
    public void updateProgress(long currentSize, long totalSize, String status) {
        // 计算百分比进度
        int progress = totalSize > 0 ? (int) ((currentSize * 100) / totalSize) : 0;
        
        // 只在非暂停状态下计算下载速度
        if (!isPaused) {
            // 计算下载速度
            long now = System.currentTimeMillis();
            if (lastUpdateTime > 0 && now - lastUpdateTime >= UPDATE_INTERVAL) {
                long timeDiff = now - lastUpdateTime;
                long bytesDiff = currentSize - lastDownloadedBytes;
                
                if (timeDiff > 0) {
                    downloadSpeed = bytesDiff * 1000f / timeDiff; // 字节/秒
                }
                
                lastUpdateTime = now;
                lastDownloadedBytes = currentSize;
                
                // 更新下载信息（速度和剩余时间）
                updateDownloadInfo(currentSize, totalSize);
            } else if (lastUpdateTime == 0) {
                lastUpdateTime = now;
                lastDownloadedBytes = currentSize;
                
                // 初始显示下载信息
                updateDownloadInfo(currentSize, totalSize);
            }
        }
        
        // 更新UI元素
        handler.post(() -> {
            // 更新进度条
            if (progressBar != null) {
                progressBar.setProgress(progress);
            }
            
            // 更新圆形进度指示器
            if (circularProgress != null) {
                circularProgress.setProgress(progress);
            }
            
            // 更新波纹进度视图
            if (rippleProgressView != null) {
                rippleProgressView.setProgress(progress / 100f);
            }
            
            // 更新状态文本
            if (tvProgressStatus != null && status != null) {
                tvProgressStatus.setText(status);
            }
        });
    }
    
    /**
     * 更新下载信息（速度和剩余时间）
     */
    private void updateDownloadInfo(long currentSize, long totalSize) {
        if (tvDownloadInfo == null) return;
        
        // 格式化下载速度
        String speedText = formatSpeed(downloadSpeed);
        
        // 计算剩余时间
        String remainingTime = "未知";
        if (downloadSpeed > 0 && totalSize > currentSize) {
            long remainingBytes = totalSize - currentSize;
            long remainingSeconds = (long) (remainingBytes / downloadSpeed);
            remainingTime = formatTime(remainingSeconds);
        }
        
        // 格式化下载信息
        String speedString = context.getString(R.string.download_speed, speedText);
        String remainingString = context.getString(R.string.download_remaining_time, remainingTime);
        final String downloadInfo = context.getString(R.string.download_info_format, speedString, remainingString);
        
        // 更新UI
        handler.post(() -> tvDownloadInfo.setText(downloadInfo));
    }
    
    /**
     * 启动波纹动画
     */
    private void startRippleAnimation() {
        if (rippleProgressView == null) return;
        
        if (rippleAnimator == null) {
            rippleAnimator = ValueAnimator.ofFloat(0f, 1f);
            rippleAnimator.setDuration(1500);
            rippleAnimator.setRepeatCount(ValueAnimator.INFINITE);
            rippleAnimator.setInterpolator(new LinearInterpolator());
            rippleAnimator.addUpdateListener(animation -> {
                float value = (float) animation.getAnimatedValue();
                rippleProgressView.setPhase(value);
            });
        }
        
        if (!rippleAnimator.isRunning()) {
            rippleAnimator.start();
        }
    }
    
    /**
     * 停止波纹动画
     */
    private void stopRippleAnimation() {
        if (rippleAnimator != null && rippleAnimator.isRunning()) {
            rippleAnimator.cancel();
        }
    }
    
    /**
     * 更新暂停/继续按钮状态
     */
    private void updatePauseResumeButton() {
        if (btnPauseResume == null) return;
        
        btnPauseResume.setText(isPaused ? 
                context.getString(R.string.resume) : 
                context.getString(R.string.pause));
        
        // 暂停时修改下载信息显示
        if (tvDownloadInfo != null) {
            if (isPaused) {
                // 在暂停状态下显示"已暂停"
                boolean isChineseLocale = Locale.getDefault().getLanguage().equals(Locale.CHINESE.getLanguage());
                tvDownloadInfo.setText(isChineseLocale ? "已暂停" : "Paused");
                
                // 停止波纹动画
                if (rippleAnimator != null && rippleAnimator.isRunning()) {
                    rippleAnimator.pause();
                }
            } else {
                // 恢复波纹动画
                if (rippleAnimator != null && rippleAnimator.isPaused()) {
                    rippleAnimator.resume();
                } else if (rippleAnimator != null && !rippleAnimator.isRunning()) {
                    rippleAnimator.start();
                }
            }
        }
    }
    
    /**
     * 格式化下载速度
     */
    private String formatSpeed(float bytesPerSecond) {
        DecimalFormat df = new DecimalFormat("0.0");
        if (bytesPerSecond < 1024) {
            return df.format(bytesPerSecond) + " B";
        } else if (bytesPerSecond < 1024 * 1024) {
            return df.format(bytesPerSecond / 1024) + " KB";
        } else {
            return df.format(bytesPerSecond / (1024 * 1024)) + " MB";
        }
    }
    
    /**
     * 格式化时间（秒）为可读字符串
     */
    private String formatTime(long seconds) {
        if (seconds < 0) return "未知";
        
        // 根据系统语言决定显示格式
        boolean isChineseLocale = Locale.getDefault().getLanguage().equals(Locale.CHINESE.getLanguage());
        
        if (seconds < 60) {
            return isChineseLocale ? seconds + " 秒" : seconds + " sec";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long remainSecs = seconds % 60;
            if (remainSecs == 0) {
                return isChineseLocale ? minutes + " 分钟" : minutes + " min";
            } else {
                return isChineseLocale ? 
                    minutes + " 分 " + remainSecs + " 秒" : 
                    minutes + " min " + remainSecs + " sec";
            }
        } else {
            long hours = seconds / 3600;
            long remainMins = (seconds % 3600) / 60;
            if (remainMins == 0) {
                return isChineseLocale ? hours + " 小时" : hours + " hour" + (hours > 1 ? "s" : "");
            } else {
                return isChineseLocale ? 
                    hours + " 小时 " + remainMins + " 分钟" : 
                    hours + " hour" + (hours > 1 ? "s" : "") + " " + remainMins + " min";
            }
        }
    }
} 