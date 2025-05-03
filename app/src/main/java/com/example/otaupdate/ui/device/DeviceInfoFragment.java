package com.example.otaupdate.ui.device;

import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.otaupdate.BuildConfig;
import com.example.otaupdate.DeviceInfoUtils;
import com.example.otaupdate.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DeviceInfoFragment extends Fragment {

    private static final String TAG = "DeviceInfoFragment";
    private static final String GITHUB_REPO_URL = "https://api.github.com/repos/LiuTengYing/FYT-OTA-UPDATE/releases/latest";

    private TextView tvCpuModel;
    private TextView tvResolution;
    private TextView tvMcuVersion;
    private TextView tvSystemBuildDate;
    private TextView tvAppBuildDate;
    private TextView tvAppVersionCard;
    private Button btnCheckAppVersion;
    
    private ExecutorService backgroundExecutor;
    private Handler mainThreadHandler;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_device_info, container, false);
        
        tvCpuModel = view.findViewById(R.id.tvCpuModel);
        tvResolution = view.findViewById(R.id.tvResolution);
        tvMcuVersion = view.findViewById(R.id.tvMcuVersion);
        tvSystemBuildDate = view.findViewById(R.id.tvSystemBuildDate);
        tvAppBuildDate = view.findViewById(R.id.tvAppBuildDate);
        tvAppVersionCard = view.findViewById(R.id.tvAppVersionCard);
        btnCheckAppVersion = view.findViewById(R.id.btnCheckAppVersion);
        
        backgroundExecutor = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());
        
        btnCheckAppVersion.setOnClickListener(v -> checkAppVersion());
        
        fetchDeviceInfo();
        return view;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (backgroundExecutor != null && !backgroundExecutor.isShutdown()) {
            backgroundExecutor.shutdown();
        }
    }
    
    private void fetchDeviceInfo() {
        backgroundExecutor.execute(() -> {
            String deviceCpuModel = DeviceInfoUtils.getCpuModel();
            String deviceResolution = DeviceInfoUtils.getScreenResolution(requireContext());
            String deviceMcuVersion = DeviceInfoUtils.getMcuVersion();
            String deviceSystemBuildDate = DeviceInfoUtils.getSystemBuildDate();
            String appBuildTime = DeviceInfoUtils.getAppBuildTime();
            String appVersion = DeviceInfoUtils.getAppVersion(requireContext());
            
            mainThreadHandler.post(() -> {
                if (isAdded()) {
                    tvCpuModel.setText(getString(R.string.cpu_model_label, deviceCpuModel));
                    tvResolution.setText(getString(R.string.resolution_label, deviceResolution));
                    tvMcuVersion.setText(getString(R.string.mcu_version_label, deviceMcuVersion));
                    tvSystemBuildDate.setText(getString(R.string.system_build_date_label, deviceSystemBuildDate));
                    tvAppBuildDate.setText(getString(R.string.app_build_date_label, appBuildTime));
                    tvAppVersionCard.setText(getString(R.string.app_version_label, appVersion));
                }
            });
        });
    }
    
    private void checkAppVersion() {
        btnCheckAppVersion.setEnabled(false);
        tvAppVersionCard.setText(R.string.app_version_label_placeholder);
        
        backgroundExecutor.execute(() -> {
            try {
                // 从GitHub获取最新版本信息
                URL url = new URL(GITHUB_REPO_URL);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "application/vnd.github.v3+json");
                
                // 检查响应码
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    // 读取响应数据
                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();
                    
                    // 解析JSON响应
                    JSONObject jsonObject = new JSONObject(response.toString());
                    String tagName = jsonObject.getString("tag_name");
                    String latestVersion = tagName.startsWith("v") ? tagName.substring(1) : tagName;
                    String currentVersion = BuildConfig.VERSION_NAME;
                    String releaseNotes = jsonObject.getString("body");
                    
                    Log.d(TAG, "当前版本: " + currentVersion + ", 最新版本: " + latestVersion);
                    
                    // 获取APK下载链接
                    String downloadUrl = null;
                    JSONArray assets = jsonObject.getJSONArray("assets");
                    for (int i = 0; i < assets.length(); i++) {
                        JSONObject asset = assets.getJSONObject(i);
                        String fileName = asset.getString("name");
                        if (fileName.endsWith(".apk")) {
                            downloadUrl = asset.getString("browser_download_url");
                            break;
                        }
                    }
                    
                    final String finalDownloadUrl = downloadUrl;
                    
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvAppVersionCard.setText(getString(R.string.app_version_label, currentVersion));
                            btnCheckAppVersion.setEnabled(true);
                            
                            // 比较版本号，判断是否需要更新
                            if (isNewerVersion(latestVersion, currentVersion) && finalDownloadUrl != null) {
                                showUpdateDialog(latestVersion, finalDownloadUrl, releaseNotes);
                            } else if (finalDownloadUrl == null) {
                                Toast.makeText(requireContext(), R.string.no_update_available, Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(requireContext(), R.string.app_up_to_date, Toast.LENGTH_SHORT).show();
                            }
                        }
                    });
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    mainThreadHandler.post(() -> {
                        if (isAdded()) {
                            tvAppVersionCard.setText(getString(R.string.app_version_label, BuildConfig.VERSION_NAME));
                            btnCheckAppVersion.setEnabled(true);
                            Toast.makeText(requireContext(), R.string.no_update_available, Toast.LENGTH_SHORT).show();
                        }
                    });
                } else {
                    throw new IOException("HTTP响应码: " + responseCode);
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "检查更新失败", e);
                mainThreadHandler.post(() -> {
                    if (isAdded()) {
                        tvAppVersionCard.setText(getString(R.string.app_version_label, BuildConfig.VERSION_NAME));
                        btnCheckAppVersion.setEnabled(true);
                        Toast.makeText(requireContext(), getString(R.string.check_update_failed, e.getMessage()), Toast.LENGTH_SHORT).show();
                    }
                });
            }
        });
    }
    
    private boolean isNewerVersion(String remoteVersion, String localVersion) {
        try {
            // 版本号格式: x.y.z
            String[] remoteParts = remoteVersion.split("\\.");
            String[] localParts = localVersion.split("\\.");
            
            // 比较主版本号、次版本号和修订号
            for (int i = 0; i < Math.min(remoteParts.length, localParts.length); i++) {
                int remotePart = Integer.parseInt(remoteParts[i]);
                int localPart = Integer.parseInt(localParts[i]);
                
                if (remotePart > localPart) {
                    return true;
                } else if (remotePart < localPart) {
                    return false;
                }
            }
            
            // 如果前面的版本号都相同，但远程版本有更多的部分（例如1.0与1.0.1）
            return remoteParts.length > localParts.length;
        } catch (NumberFormatException e) {
            Log.e(TAG, "版本号比较失败", e);
            return false;
        }
    }
    
    private void showUpdateDialog(String version, String downloadUrl, String releaseNotes) {
        new AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.new_version_available) + ": " + version)
            .setMessage(getString(R.string.update_message, releaseNotes))
            .setPositiveButton(R.string.download_update, (dialog, which) -> {
                downloadAndInstallApk(downloadUrl, version);
            })
            .setNegativeButton(R.string.later, null)
            .show();
    }
    
    private void downloadAndInstallApk(String downloadUrl, String version) {
        // 创建进度对话框
        ProgressDialog progressDialog = new ProgressDialog(requireContext());
        progressDialog.setTitle(getString(R.string.downloading));
        progressDialog.setMessage(getString(R.string.downloading_update, version));
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(100);
        progressDialog.setCancelable(false);
        progressDialog.show();
        
        backgroundExecutor.execute(() -> {
            try {
                // 创建下载目录
                File downloadDir = new File(requireContext().getExternalFilesDir(null), "updates");
                if (!downloadDir.exists()) {
                    downloadDir.mkdirs();
                }
                
                // 下载APK文件
                File outputFile = new File(downloadDir, "OTAUpdate-" + version + ".apk");
                URL url = new URL(downloadUrl);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.connect();
                
                int fileLength = connection.getContentLength();
                InputStream input = new BufferedInputStream(connection.getInputStream());
                OutputStream output = new FileOutputStream(outputFile);
                
                byte[] data = new byte[4096];
                long total = 0;
                int count;
                while ((count = input.read(data)) != -1) {
                    total += count;
                    if (fileLength > 0) {
                        final int progress = (int) (total * 100 / fileLength);
                        mainThreadHandler.post(() -> progressDialog.setProgress(progress));
                    }
                    output.write(data, 0, count);
                }
                
                output.flush();
                output.close();
                input.close();
                
                mainThreadHandler.post(() -> {
                    progressDialog.dismiss();
                    installApk(outputFile);
                });
            } catch (Exception e) {
                Log.e(TAG, "下载APK失败", e);
                mainThreadHandler.post(() -> {
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(), getString(R.string.download_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }
    
    private void installApk(File apkFile) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Android 7.0及以上需要使用FileProvider
            intent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            String authority = requireContext().getPackageName() + ".provider";
            apkUri = FileProvider.getUriForFile(requireContext(), authority, apkFile);
        } else {
            // Android 7.0以下可以直接使用文件URI
            apkUri = Uri.fromFile(apkFile);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        
        intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_app_to_open_apk, Toast.LENGTH_LONG).show();
        }
    }
}