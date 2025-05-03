package com.example.otaupdate;

import android.util.Log;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class FileUtils {
    private static final String TAG = "FileUtils";
    private static final int BUFFER_SIZE = 8192;

    public interface ProgressCallback {
        void onProgress(int progress);
    }

    private FileUtils() {
    }

    public static boolean unzip(String zipFilePath, String destDirectory) {
        return unzip(zipFilePath, destDirectory, null);
    }

    public static boolean unzip(String zipFilePath, String destDirectory, @Nullable ProgressCallback callback) {
        File destDir = new File(destDirectory);
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "Failed mkdir: " + destDirectory);
            return false;
        }
        ZipInputStream zipIn = null;
        InputStream checkStream = null;
        try {
            File zipFile = new File(zipFilePath);
            long totalSize = zipFile.length();
            int lastProgress = -1;
            if (!zipFile.exists() || !zipFile.isFile() || totalSize == 0) {
                Log.e(TAG, "Zip file invalid: " + zipFilePath);
                return false;
            }
            checkStream = new FileInputStream(zipFilePath);
            zipIn = new ZipInputStream(checkStream);
            ZipEntry entry = zipIn.getNextEntry();
            byte[] buffer = new byte[BUFFER_SIZE];
            long currentSize = 0;

            // 检查是否是MCU更新文件
            boolean isMcuUpdate = zipFile.getName().matches("L\\d+_MCU\\.zip");
            Log.d(TAG, "Unzipping file: " + zipFile.getName() + ", isMcuUpdate: " + isMcuUpdate);

            while (entry != null) {
                // 对于MCU更新文件，去掉第一层目录
                String entryName = entry.getName();
                if (isMcuUpdate && entryName.startsWith("L")) {
                    int firstSlash = entryName.indexOf('/');
                    if (firstSlash != -1) {
                        entryName = entryName.substring(firstSlash + 1);
                        if (entryName.isEmpty()) {
                            zipIn.closeEntry();
                            entry = zipIn.getNextEntry();
                            continue;
                        }
                    }
                }

                File newFile = new File(destDir, entryName);
                String canonicalDestinationPath = destDir.getCanonicalPath();
                String canonicalNewFilePath = newFile.getCanonicalPath();
                
                if (!canonicalNewFilePath.startsWith(canonicalDestinationPath + File.separator)) {
                    Log.w(TAG, "Skipping entry outside destination directory: " + entryName);
                    zipIn.closeEntry();
                    entry = zipIn.getNextEntry();
                    continue;
                }

                if (entry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed mkdir: " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed mkdir parent: " + parent);
                    }
                    FileOutputStream fos = null;
                    try {
                        fos = new FileOutputStream(newFile);
                        int len;
                        while ((len = zipIn.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    } finally {
                        closeQuietly(fos);
                    }
                }

                long entryCompSize = entry.getCompressedSize();
                if (callback != null && totalSize > 0 && entryCompSize > 0) {
                    currentSize += entryCompSize;
                    int progress = (int) ((currentSize * 100) / totalSize);
                    progress = Math.min(progress, 100);
                    if (progress != lastProgress) {
                        lastProgress = progress;
                        callback.onProgress(progress);
                    }
                }
                zipIn.closeEntry();
                entry = zipIn.getNextEntry();
            }
            if (callback != null && lastProgress < 100) callback.onProgress(100);
            return true;
        } catch (IOException e) {
            Log.e(TAG, "Unzip failed", e);
            deleteRecursive(destDir);
            return false;
        } finally {
            closeQuietly(zipIn);
            if (zipIn == null) closeQuietly(checkStream);
        }
    }

    public static boolean moveFilesFromDirectory(String sourceDirPath, String destDirPath) {
        File sourceDir = new File(sourceDirPath);
        File destDir = new File(destDirPath);
        Log.d(TAG, "[Move] Starting move from: " + sourceDirPath + " to " + destDirPath);
        if (!sourceDir.exists() || !sourceDir.isDirectory()) {
            Log.e(TAG, "[Move] Bad source dir: " + sourceDirPath);
            return false;
        }
        if (!destDir.exists() && !destDir.mkdirs()) {
            Log.e(TAG, "[Move] Failed mkdir dest: " + destDirPath);
            return false;
        }
        if (!destDir.isDirectory()) {
            Log.e(TAG, "[Move] Dest not dir: " + destDirPath);
            return false;
        }
        if (!destDir.canWrite()) {
            Log.e(TAG, "[Move] No write permission for dest dir: " + destDirPath);
            return false;
        }

        // 获取源目录中的所有文件和文件夹
        File[] files = sourceDir.listFiles();
        if (files == null || files.length == 0) {
            Log.w(TAG, "[Move] Source directory empty: " + sourceDirPath);
            return true;
        }

        // 判断是否是系统升级包 - 检查是否有特殊目录
        boolean isSystemUpdate = false;
        boolean isMcuUpdate = false;
        String dirName = sourceDir.getName();
        Log.d(TAG, "[Move] Directory name: " + dirName);
        
        // 检查是否包含特殊文件夹，判断是系统更新
        for (File file : files) {
            if (file.isDirectory()) {
                String name = file.getName().toLowerCase();
                if (name.equals("lsec_updatesh") || name.equals("oem") || name.equals("vaudioshow")) {
                    Log.d(TAG, "[Move] Found special folder: " + name + " - this is a system update");
                    isSystemUpdate = true;
                    break;
                }
            }
        }
        
        // 如果没有特殊文件夹，检查是否有6316开头的ZIP文件
        if (!isSystemUpdate) {
            for (File file : files) {
                if (file.isFile() && file.getName().startsWith("6316") && file.getName().endsWith(".zip")) {
                    Log.d(TAG, "[Move] Found system update zip file: " + file.getName());
                    isSystemUpdate = true;
                    break;
                }
            }
        }
        
        // 检查目录名称判断是否是MCU更新
        isMcuUpdate = dirName.matches("L\\d+_MCU");
        
        Log.d(TAG, "[Move] Update type detection: isSystemUpdate=" + isSystemUpdate + 
                ", isMcuUpdate=" + isMcuUpdate);
        
        // 如果是系统更新，保留完整目录结构
        if (isSystemUpdate) {
            Log.d(TAG, "[Move] Handling system update - preserving entire directory structure");
            boolean success = true;
            
            // 复制每个文件和文件夹到目标位置，保留完整结构
            for (File file : files) {
                String fileName = file.getName();
                File targetFile = new File(destDir, fileName);
                
                if (file.isDirectory()) {
                    Log.d(TAG, "[Move] Processing directory: " + fileName);
                    
                    // 判断是否是特殊目录
                    boolean isSpecialDir = fileName.toLowerCase().equals("lsec_updatesh") || 
                                          fileName.toLowerCase().equals("oem") || 
                                          fileName.toLowerCase().equals("vaudioshow");
                    
                    // 如果目标已存在，先删除
                    if (targetFile.exists()) {
                        if (!deleteRecursive(targetFile)) {
                            Log.e(TAG, "[Move] Failed to delete existing directory: " + targetFile.getAbsolutePath());
                            if (isSpecialDir) {
                                Log.e(TAG, "[Move] Critical error: failed to clean special directory: " + fileName);
                                return false;
                            }
                            success = false;
                            continue;
                        }
                    }
                    
                    // 创建目标目录
                    if (!targetFile.mkdir()) {
                        Log.e(TAG, "[Move] Failed to create directory: " + targetFile.getAbsolutePath());
                        if (isSpecialDir) {
                            Log.e(TAG, "[Move] Critical error: failed to create special directory: " + fileName);
                            return false;
                        }
                        success = false;
                        continue;
                    }
                    
                    // 递归复制目录内容
                    try {
                        if (!copyDirectoryRecursively(file, targetFile)) {
                            Log.e(TAG, "[Move] Failed to copy directory contents: " + fileName);
                            if (isSpecialDir) {
                                Log.e(TAG, "[Move] Critical error: failed to copy special directory content: " + fileName);
                                return false;
                            }
                            success = false;
                        } else {
                            Log.d(TAG, "[Move] Successfully copied directory: " + fileName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[Move] Error copying directory: " + fileName, e);
                        if (isSpecialDir) {
                            Log.e(TAG, "[Move] Critical error: exception while copying special directory: " + fileName, e);
                            return false;
                        }
                        success = false;
                    }
                } else {
                    // 如果是文件，直接复制到目标位置
                    Log.d(TAG, "[Move] Processing file: " + fileName);
                    
                    if (targetFile.exists()) {
                        if (!targetFile.delete()) {
                            Log.e(TAG, "[Move] Failed to delete existing file: " + targetFile.getAbsolutePath());
                            success = false;
                            continue;
                        }
                    }
                    
                    try {
                        if (!copyFile(file, targetFile)) {
                            Log.e(TAG, "[Move] Failed to copy file: " + fileName);
                            success = false;
                        } else {
                            Log.d(TAG, "[Move] Successfully copied file: " + fileName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[Move] Error copying file: " + fileName, e);
                        success = false;
                    }
                }
            }
            
            // 验证特殊文件夹是否存在
            boolean hasLsecUpdatesh = new File(destDir, "lsec_updatesh").exists();
            boolean hasOem = new File(destDir, "oem").exists();
            boolean hasVaudioshow = new File(destDir, "vaudioshow").exists();
            
            Log.d(TAG, "[Move] Special folders verification: lsec_updatesh=" + hasLsecUpdatesh + 
                    ", oem=" + hasOem + ", vaudioshow=" + hasVaudioshow);
            
            // 只有在成功时才删除源目录
            if (success) {
                if (!deleteRecursive(sourceDir)) {
                    Log.e(TAG, "[Move] Failed to delete source directory: " + sourceDir.getAbsolutePath());
                }
            }
            
            return success;
        }
        // MCU升级包处理逻辑保持不变
        else if (isMcuUpdate) {
            Log.d(TAG, "[Move] Detected MCU update directory: " + dirName + ", moving its contents");
            if (files != null) {
                boolean overallSuccess = true;
                for (File file : files) {
                    if (file.isDirectory()) {
                        // 如果是子目录，递归移动其内容
                        if (!moveFilesFromDirectory(file.getAbsolutePath(), destDirPath)) {
                            overallSuccess = false;
                        }
                    } else {
                        // 如果是文件，直接移动到目标目录
                        File targetFile = new File(destDir, file.getName());
                        if (targetFile.exists()) {
                            if (!deleteRecursive(targetFile)) {
                                Log.e(TAG, "[Move] Failed to delete existing file: " + targetFile);
                                overallSuccess = false;
                                continue;
                            }
                        }
                        if (!file.renameTo(targetFile)) {
                            Log.e(TAG, "[Move] Failed to move file: " + file.getAbsolutePath());
                            overallSuccess = false;
                        } else {
                            Log.d(TAG, "[Move] Successfully moved file: " + file.getName());
                        }
                    }
                }
                
                // 删除源目录及其父目录（如果是MCU更新目录）
                if (overallSuccess) {
                    // 首先删除当前目录
                    if (!deleteRecursive(sourceDir)) {
                        Log.e(TAG, "[Move] Failed to delete source directory: " + sourceDir.getAbsolutePath());
                        overallSuccess = false;
                    } else {
                        Log.d(TAG, "[Move] Successfully deleted source directory: " + sourceDir.getAbsolutePath());
                        
                        // 获取父目录
                        File parentDir = sourceDir.getParentFile();
                        if (parentDir != null && parentDir.getName().equals(dirName)) {
                            // 如果父目录也是同名的MCU目录，删除它
                            if (!deleteRecursive(parentDir)) {
                                Log.e(TAG, "[Move] Failed to delete parent MCU directory: " + parentDir.getAbsolutePath());
                            } else {
                                Log.d(TAG, "[Move] Successfully deleted parent MCU directory: " + parentDir.getAbsolutePath());
                            }
                        }
                    }
                }
                return overallSuccess;
            }
            return true;
        }
        // 系统应用更新处理逻辑 - 同样使用复制而非移动
        else {
            Log.d(TAG, "[Move] Processing system app update, moving all files to root");
            boolean overallSuccess = true;
            for (File file : files) {
                String fileName = file.getName();
                File targetFile = new File(destDir, fileName);
                
                if (targetFile.exists()) {
                    if (!deleteRecursive(targetFile)) {
                        Log.e(TAG, "[Move] Failed to delete existing file/dir: " + targetFile.getAbsolutePath());
                        overallSuccess = false;
                        continue;
                    }
                }
                
                if (file.isDirectory()) {
                    // 处理目录
                    if (!copyDirectoryRecursively(file, targetFile)) {
                        Log.e(TAG, "[Move] Failed to copy directory: " + fileName);
                        overallSuccess = false;
                    } else {
                        Log.d(TAG, "[Move] Successfully copied directory: " + fileName);
                    }
                } else {
                    // 处理文件
                    try {
                        if (!copyFile(file, targetFile)) {
                            Log.e(TAG, "[Move] Failed to copy file: " + fileName);
                            overallSuccess = false;
                        } else {
                            Log.d(TAG, "[Move] Successfully copied file: " + fileName);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[Move] Error copying file: " + fileName, e);
                        overallSuccess = false;
                    }
                }
            }
            
            // 只有在成功时才删除源目录
            if (overallSuccess) {
                if (!deleteRecursive(sourceDir)) {
                    Log.e(TAG, "[Move] Failed to delete source directory: " + sourceDir.getAbsolutePath());
                }
            }
            
            return overallSuccess;
        }
    }
    
    /**
     * 递归复制目录及其内容
     */
    private static boolean copyDirectoryRecursively(File sourceDir, File destDir) {
        if (sourceDir == null || !sourceDir.exists() || !sourceDir.isDirectory()) {
            return false;
        }
        
        if (!destDir.exists()) {
            if (!destDir.mkdirs()) {
                Log.e(TAG, "[Copy] Failed to create destination directory: " + destDir.getAbsolutePath());
                return false;
            }
        }
        
        boolean success = true;
        File[] files = sourceDir.listFiles();
        
        if (files != null) {
            // 遍历并复制每个文件和目录
            for (File file : files) {
                File targetFile = new File(destDir, file.getName());
                
                if (file.isDirectory()) {
                    // 创建目标子目录
                    if (!targetFile.exists() && !targetFile.mkdir()) {
                        Log.e(TAG, "[Copy] Failed to create subdirectory: " + targetFile.getAbsolutePath());
                        success = false;
                        continue;
                    }
                    
                    // 递归复制子目录内容
                    if (!copyDirectoryRecursively(file, targetFile)) {
                        success = false;
                    }
                } else {
                    // 复制文件
                    try {
                        if (!copyFile(file, targetFile)) {
                            Log.e(TAG, "[Copy] Failed to copy file: " + file.getName());
                            success = false;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "[Copy] Error copying file: " + file.getName(), e);
                        success = false;
                    }
                }
            }
        }
        
        return success;
    }

    public static boolean copyFile(File sourceFile, File destFile) throws IOException {
        if (!sourceFile.exists()) return false;
        if (sourceFile.isDirectory()) {
            Log.d(TAG, "Copying directory: " + sourceFile.getPath());
            if (!destFile.exists() && !destFile.mkdirs()) {
                Log.e(TAG, "Copy failed: mkdirs dest " + destFile.getPath());
                return false;
            }
            boolean success = true;
            File[] files = sourceFile.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (!copyFile(file, new File(destFile, file.getName()))) success = false;
                }
            }
            return success;
        }
        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(sourceFile);
            fos = new FileOutputStream(destFile);
            byte[] buffer = new byte[BUFFER_SIZE];
            int length;
            while ((length = fis.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            return true;
        } finally {
            closeQuietly(fis);
            closeQuietly(fos);
        }
    }

    public static boolean deleteRecursive(File fileOrDirectory) {
        return deleteRecursiveWithRetry(fileOrDirectory, 3);
    }

    private static boolean deleteRecursiveWithRetry(File f, int r) {
        if (f.isDirectory()) {
            File[] c = f.listFiles();
            if (c != null) for (File i : c) if (!deleteRecursiveWithRetry(i, r)) return false;
        }
        return deleteFileWithRetry(f, r);
    }

    private static boolean deleteFileWithRetry(File f, int r) {
        boolean d = f.delete();
        int a = 0;
        while (!d && a < r) {
            try {
                Thread.sleep(200);
                System.gc();
                d = f.delete();
                a++;
                if (d) Log.d(TAG, "Deleted on retry " + a + ": " + f);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.e(TAG, "Delete retry interrupted", e);
                break;
            }
        }
        if (!d) Log.w(TAG, "Failed delete after retries: " + f);
        return d;
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File df = new File(destinationDir, zipEntry.getName());
        String ddp = destinationDir.getCanonicalPath();
        String dfp = df.getCanonicalPath();
        if (!dfp.startsWith(ddp + File.separator)) {
            Log.e(TAG, "Entry outside target dir: " + zipEntry.getName());
            return null;
        }
        return df;
    }

    public static void closeQuietly(InputStream s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void closeQuietly(OutputStream s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    public static void closeQuietly(ZipInputStream s) {
        if (s != null) {
            try {
                s.closeEntry();
            } catch (IOException ignored) {
            }
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 检查系统关键文件夹是否存在
     */
    private static boolean checkSystemFoldersExist(File dir) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) {
            return false;
        }
        
        boolean hasLsecUpdatesh = new File(dir, "lsec_updatesh").exists();
        boolean hasOem = new File(dir, "oem").exists();
        boolean hasVaudioshow = new File(dir, "vaudioshow").exists();
        
        Log.d(TAG, "[SystemCheck] Critical folders check: lsec_updatesh=" + hasLsecUpdatesh + 
              ", oem=" + hasOem + ", vaudioshow=" + hasVaudioshow);
        
        return hasLsecUpdatesh || hasOem || hasVaudioshow;
    }
}