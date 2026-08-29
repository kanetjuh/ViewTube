package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.youtube;

import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.MediaItemService;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves YouTube quality chips without changing playback behaviour.
 *
 * Browse data is used first. Some MediaServiceCore revisions drop YouTube TV's separate 4K badge
 * while flattening a tile, so on focus we fall back to the same format-info service SmartTube uses
 * for playback and inspect the available video formats. Results are cached per video id.
 */
public final class YouTubeQualityResolver {
    private static final String NO_QUALITY = "-";
    private static final Map<String, String> sCache = new ConcurrentHashMap<>();
    private static final Set<String> sInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public interface Callback {
        void onQuality(String quality);
    }

    private YouTubeQualityResolver() {
    }

    public static String detectFromVideo(Video video) {
        if (video == null) {
            return null;
        }

        String quality = findQuality(video.getSecondTitle());
        if (quality == null) {
            quality = normalizeQuality(video.badge);
        }
        if (quality == null && !TextUtils.isEmpty(video.videoId)) {
            String cached = sCache.get(video.videoId);
            quality = NO_QUALITY.equals(cached) ? null : cached;
        }
        return quality;
    }

    public static String normalizeQuality(String value) {
        if (TextUtils.isEmpty(value)) {
            return null;
        }

        String text = value.trim().toUpperCase(Locale.US).replace(" ", "");
        if (text.equals("8K") || text.equals("4320P")) {
            return "8K";
        }
        if (text.equals("4K") || text.equals("2160P") || text.equals("UHD")) {
            return "4K";
        }
        if (text.equals("HDR")) {
            return "HDR";
        }
        if (text.equals("HD") || text.equals("1080P")) {
            return "HD";
        }
        return null;
    }

    public static void resolve(Video video, Callback callback) {
        if (video == null || callback == null || TextUtils.isEmpty(video.videoId)) {
            return;
        }

        String immediate = detectFromVideo(video);
        if (immediate != null) {
            callback.onQuality(immediate);
            return;
        }

        String cached = sCache.get(video.videoId);
        if (cached != null) {
            if (!NO_QUALITY.equals(cached)) {
                callback.onQuality(cached);
            }
            return;
        }

        if (!sInFlight.add(video.videoId)) {
            return;
        }

        try {
            MediaItemService service = YouTubeServiceManager.instance().getMediaItemService();
            service.getFormatInfoObserve(video.videoId)
                    .take(1)
                    .subscribe(formatInfo -> {
                        String quality = detectFromFormatInfo(formatInfo);
                        sCache.put(video.videoId, quality != null ? quality : NO_QUALITY);
                        sInFlight.remove(video.videoId);
                        if (quality != null) {
                            callback.onQuality(quality);
                        }
                    }, error -> {
                        sCache.put(video.videoId, NO_QUALITY);
                        sInFlight.remove(video.videoId);
                    });
        } catch (Throwable error) {
            sCache.put(video.videoId, NO_QUALITY);
            sInFlight.remove(video.videoId);
        }
    }

    private static String findQuality(CharSequence source) {
        if (source == null) {
            return null;
        }

        String upper = source.toString().toUpperCase(Locale.US);
        if (containsToken(upper, "8K") || upper.contains("4320P")) {
            return "8K";
        }
        if (containsToken(upper, "4K") || upper.contains("2160P") || containsToken(upper, "UHD")) {
            return "4K";
        }
        if (containsToken(upper, "HDR")) {
            return "HDR";
        }
        return null;
    }

    private static boolean containsToken(String source, String token) {
        return source.matches(".*(^|[^A-Z0-9])" + token + "([^A-Z0-9]|$).*");
    }

    private static String detectFromFormatInfo(Object formatInfo) {
        QualityState state = new QualityState();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        scan(formatInfo, "formatInfo", 0, visited, state);
        return state.best;
    }

    private static void scan(Object value, String name, int depth, Set<Object> visited, QualityState state) {
        if (value == null || depth > 5 || state.is8K()) {
            return;
        }

        if (value instanceof CharSequence) {
            state.acceptText(value.toString());
            return;
        }

        if (value instanceof Number) {
            if (name != null && name.toLowerCase(Locale.US).contains("height")) {
                state.acceptHeight(((Number) value).intValue());
            }
            return;
        }

        Class<?> type = value.getClass();
        if (type.isEnum() || type == Boolean.class || type == Character.class) {
            return;
        }

        if (!visited.add(value)) {
            return;
        }

        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 80);
            for (int i = 0; i < length; i++) {
                scan(Array.get(value, i), name, depth + 1, visited, state);
            }
            return;
        }

        if (value instanceof Iterable) {
            int count = 0;
            for (Object item : (Iterable<?>) value) {
                scan(item, name, depth + 1, visited, state);
                if (++count >= 80 || state.is8K()) {
                    break;
                }
            }
            return;
        }

        if (value instanceof Map) {
            int count = 0;
            for (Object item : ((Map<?, ?>) value).entrySet()) {
                scan(item, name, depth + 1, visited, state);
                if (++count >= 80 || state.is8K()) {
                    break;
                }
            }
            return;
        }

        // Invoke only side-effect-free getters that commonly expose MediaItemFormatInfo/MediaFormat
        // data. Reflection keeps this compatible with multiple MediaServiceCore revisions.
        String[] getters = {
                "getHeight", "getQuality", "getQualityLabel", "getResolution",
                "getAdaptiveFormats", "getVideoFormats", "getFormats", "getUrlFormats",
                "getSabrFormats", "getHlsFormats", "getDashFormats", "getAllFormats"
        };
        for (String getter : getters) {
            try {
                Method method = type.getMethod(getter);
                if (method.getParameterTypes().length == 0) {
                    scan(method.invoke(value), getter, depth + 1, visited, state);
                }
            } catch (Throwable ignored) {
                // Different MediaServiceCore versions expose different format APIs.
            }
        }

        // Some implementations keep format lists/height as private fields instead of public
        // getters. Only inspect fields whose names clearly describe format/quality data.
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Field[] fields;
            try {
                fields = current.getDeclaredFields();
            } catch (Throwable ignored) {
                continue;
            }

            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String fieldName = field.getName().toLowerCase(Locale.US);
                if (!(fieldName.contains("height") || fieldName.contains("quality") ||
                        fieldName.contains("resolution") || fieldName.contains("format") ||
                        fieldName.contains("adaptive") || fieldName.contains("sabr"))) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    scan(field.get(value), field.getName(), depth + 1, visited, state);
                } catch (Throwable ignored) {
                    // Hidden/reflection restrictions: simply skip this field.
                }
            }
        }
    }

    private static final class QualityState {
        private String best;

        void acceptHeight(int height) {
            if (height >= 4320) {
                best = "8K";
            } else if (height >= 2160 && !is8K()) {
                best = "4K";
            }
        }

        void acceptText(String text) {
            String found = findQuality(text);
            if ("8K".equals(found)) {
                best = "8K";
            } else if ("4K".equals(found) && !is8K()) {
                best = "4K";
            } else if (best == null && found != null) {
                best = found;
            }
        }

        boolean is8K() {
            return "8K".equals(best);
        }
    }
}
