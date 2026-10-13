package dev.experimental.rmgu.bootprobe;

import android.graphics.*;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

/** Draws immediately. It neither holds boot-animation completion nor runs root code. */
public final class ProbeWallpaperService extends WallpaperService {
    @Override public void onCreate() {
        super.onCreate();
        ProbeRuntime.start(this, "wallpaper_service_created");
    }

    @Override public Engine onCreateEngine() { return new ProbeEngine(); }

    private final class ProbeEngine extends Engine {
        @Override public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            ProbeRuntime.engine(ProbeWallpaperService.this, isPreview());
        }
        @Override public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            ProbeRuntime.event(ProbeWallpaperService.this, "wallpaper_surface_created");
            draw(holder);
        }
        @Override public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            draw(holder);
        }
        @Override public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);
            if (visible) draw(getSurfaceHolder());
        }
        @Override public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            super.onSurfaceRedrawNeeded(holder);
            draw(holder);
        }

        private void draw(SurfaceHolder holder) {
            if (!holder.getSurface().isValid()) return;
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if (canvas == null) return;
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setShader(new LinearGradient(0, 0, canvas.getWidth(), canvas.getHeight(),
                        Color.rgb(12, 23, 42), Color.rgb(27, 66, 88), Shader.TileMode.CLAMP));
                canvas.drawRect(0, 0, canvas.getWidth(), canvas.getHeight(), paint);
            } finally { if (canvas != null) holder.unlockCanvasAndPost(canvas); }
        }
    }
}
