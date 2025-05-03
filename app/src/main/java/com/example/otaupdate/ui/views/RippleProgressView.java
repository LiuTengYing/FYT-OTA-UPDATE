package com.example.otaupdate.ui.views;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.example.otaupdate.R;

/**
 * 自定义波纹进度视图，用于显示下载进度的动态波纹效果
 */
public class RippleProgressView extends View {
    private Paint ripplePaint;
    private Paint backgroundPaint;
    private float progress = 0f;
    private float phase = 0f; // 动画相位
    private float rippleWidth = 20f; // 波纹宽度
    private int rippleColor = Color.BLUE; // 波纹颜色
    private int backgroundColor = Color.parseColor("#33000000"); // 背景颜色

    public RippleProgressView(Context context) {
        super(context);
        init(null);
    }

    public RippleProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    public RippleProgressView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        // 解析自定义属性
        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.RippleProgressView);
            rippleColor = a.getColor(R.styleable.RippleProgressView_rippleColor, rippleColor);
            backgroundColor = a.getColor(R.styleable.RippleProgressView_backgroundColor, backgroundColor);
            rippleWidth = a.getDimension(R.styleable.RippleProgressView_rippleWidth, rippleWidth);
            a.recycle();
        }

        // 初始化波纹画笔
        ripplePaint = new Paint();
        ripplePaint.setAntiAlias(true);
        ripplePaint.setColor(rippleColor);
        ripplePaint.setStyle(Paint.Style.STROKE);
        ripplePaint.setStrokeWidth(rippleWidth);

        // 初始化背景画笔
        backgroundPaint = new Paint();
        backgroundPaint.setAntiAlias(true);
        backgroundPaint.setColor(backgroundColor);
        backgroundPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float width = getWidth();
        float height = getHeight();
        float radius = Math.min(width, height) / 2 - rippleWidth / 2;

        // 只绘制波纹，不再绘制背景圆和进度圆弧
        // 计算要绘制的波纹数量
        int rippleCount = 3;
        for (int i = 0; i < rippleCount; i++) {
            // 每个波纹有不同的alpha值和大小
            float ripplePhase = (phase + (float) i / rippleCount) % 1.0f;
            float rippleRadius = radius * (0.5f + ripplePhase * 0.5f);
            
            // 根据相位计算透明度，当波纹扩散到最大时透明度为0
            int alpha = (int) (255 * (1.0f - ripplePhase));
            ripplePaint.setAlpha(alpha);
            
            // 绘制波纹圆
            canvas.drawCircle(width / 2, height / 2, rippleRadius, ripplePaint);
        }
    }

    /**
     * 设置进度值 (0.0-1.0)
     */
    public void setProgress(float progress) {
        this.progress = Math.max(0f, Math.min(1f, progress));
        invalidate();
    }

    /**
     * 设置动画相位 (0.0-1.0)
     */
    public void setPhase(float phase) {
        this.phase = phase;
        invalidate();
    }

    /**
     * 设置波纹颜色
     */
    public void setRippleColor(int color) {
        this.rippleColor = color;
        ripplePaint.setColor(color);
        invalidate();
    }
} 