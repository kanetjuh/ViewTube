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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Resolves the highest ACTUAL video stream resolution exposed by MediaServiceCore.
 *
 * Resolution is NEVER inferred from:
 * - video title
 * - browse badge
 * - secondTitle / feed metadata
 *
 * Mapping used by the ViewTube UI:
 * >= 4320p -> 8K
 * >= 2160p -> 4K
 * >= 1440p -> 2K
 * <  1440p -> no 2K/4K/8K chip
 */
public final class YouTubeQualityResolver {
    private static final String NO_QUALITY = "-";

    private static final Map<String, String> sCache = new ConcurrentHashMap<>();
    private static final Set<String> sInFlight =
            Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, List<Callback>> sPending =
            new ConcurrentHashMap<>();

    public interface Callback {
        void onQuality(String quality);
    }

    private YouTubeQualityResolver() {
    }

    public static void resolve(Video video, Callback callback) {
        if (video == null || callback == null || TextUtils.isEmpty(video.videoId)) {
            return;
        }

        final String videoId = video.videoId;
        String cached = sCache.get(videoId);

        if (cached != null) {
            if (!NO_QUALITY.equals(cached)) {
                callback.onQuality(cached);
            }

            return;
        }

        sPending
                .computeIfAbsent(videoId, key -> new CopyOnWriteArrayList<>())
                .add(callback);

        // Only one network/format lookup per video id.
        if (!sInFlight.add(videoId)) {
            return;
        }

        try {
            MediaItemService service =
                    YouTubeServiceManager.instance().getMediaItemService();

            service.getFormatInfoObserve(videoId)
                    .take(1)
                    .subscribe(
                            formatInfo -> finish(
                                    videoId,
                                    detectFromFormatInfo(formatInfo),
                                    true
                            ),
                            error -> finish(videoId, null, false)
                    );
        } catch (Throwable error) {
            finish(videoId, null, false);
        }
    }

    private static void finish(
            String videoId,
            String quality,
            boolean cacheResult) {

        if (cacheResult) {
            sCache.put(
                    videoId,
                    quality != null ? quality : NO_QUALITY
            );
        }

        sInFlight.remove(videoId);

        List<Callback> callbacks = sPending.remove(videoId);

        if (callbacks == null || quality == null) {
            return;
        }

        for (Callback callback : callbacks) {
            if (callback != null) {
                callback.onQuality(quality);
            }
        }
    }

    private static String detectFromFormatInfo(Object formatInfo) {
        QualityState state = new QualityState();

        Set<Object> visited =
                Collections.newSetFromMap(new IdentityHashMap<>());

        scan(
                formatInfo,
                "formatInfo",
                0,
                visited,
                state
        );

        return state.toLabel();
    }

    private static void scan(
            Object value,
            String sourceName,
            int depth,
            Set<Object> visited,
            QualityState state) {

        if (value == null || depth > 7 || state.rank >= 3) {
            return;
        }

        if (value instanceof CharSequence) {
            if (isFormatSourceName(sourceName)) {
                state.acceptText(value.toString());
            }

            return;
        }

        if (value instanceof Number) {
            if (sourceName != null &&
                    sourceName.toLowerCase(Locale.US).contains("height")) {
                state.acceptHeight(((Number) value).intValue());
            }

            return;
        }

        Class<?> type = value.getClass();

        if (type.isEnum()) {
            if (isFormatSourceName(sourceName)) {
                state.acceptText(value.toString());
            }

            return;
        }

        if (type == Boolean.class || type == Character.class) {
            return;
        }

        if (!visited.add(value)) {
            return;
        }

        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 100);

            for (int i = 0; i < length; i++) {
                scan(
                        Array.get(value, i),
                        sourceName,
                        depth + 1,
                        visited,
                        state
                );

                if (state.rank >= 3) {
                    break;
                }
            }

            return;
        }

        if (value instanceof Iterable) {
            int count = 0;

            for (Object item : (Iterable<?>) value) {
                scan(
                        item,
                        sourceName,
                        depth + 1,
                        visited,
                        state
                );

                if (++count >= 100 || state.rank >= 3) {
                    break;
                }
            }

            return;
        }

        if (value instanceof Map) {
            int count = 0;

            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = String.valueOf(entry.getKey());

                if (isFormatSourceName(key)) {
                    scan(
                            entry.getValue(),
                            key,
                            depth + 1,
                            visited,
                            state
                    );
                }

                if (++count >= 100 || state.rank >= 3) {
                    break;
                }
            }

            return;
        }

        // MediaServiceCore concrete format models differ between revisions.
        // Only inspect getters that explicitly expose stream format data.
        String[] getters = {
                "getHeight",
                "getQuality",
                "getQualityLabel",
                "getResolution",
                "getAdaptiveFormats",
                "getVideoFormats",
                "getFormats",
                "getUrlFormats",
                "getSabrFormats",
                "getHlsFormats",
                "getDashFormats",
                "getAllFormats"
        };

        for (String getter : getters) {
            try {
                Method method = type.getMethod(getter);

                if (method.getParameterTypes().length == 0) {
                    scan(
                            method.invoke(value),
                            getter,
                            depth + 1,
                            visited,
                            state
                    );
                }
            } catch (Throwable ignored) {
                // Different MediaServiceCore versions expose different getters.
            }

            if (state.rank >= 3) {
                return;
            }
        }

        // Some versions keep the values in fields rather than public getters.
        // Generic text/title fields are intentionally never inspected.
        for (Class<?> current = type;
             current != null && current != Object.class;
             current = current.getSuperclass()) {

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

                if (!isFormatSourceName(field.getName())) {
                    continue;
                }

                try {
                    field.setAccessible(true);

                    scan(
                            field.get(value),
                            field.getName(),
                            depth + 1,
                            visited,
                            state
                    );
                } catch (Throwable ignored) {
                    // Reflection restrictions: skip safely.
                }

                if (state.rank >= 3) {
                    return;
                }
            }
        }
    }

    private static boolean isFormatSourceName(String name) {
        if (TextUtils.isEmpty(name)) {
            return false;
        }

        String value = name.toLowerCase(Locale.US);

        return value.contains("height") ||
                value.contains("quality") ||
                value.contains("resolution") ||
                value.contains("format") ||
                value.contains("adaptive") ||
                value.contains("stream") ||
                value.contains("sabr") ||
                value.contains("dash") ||
                value.contains("hls");
    }

    private static int detectTextRank(String text) {
        if (TextUtils.isEmpty(text)) {
            return 0;
        }

        String value = text
                .trim()
                .toUpperCase(Locale.US)
                .replace(" ", "");

        if (value.contains("4320P") ||
                value.contains("7680X4320") ||
                value.contains("7680×4320") ||
                containsToken(value, "8K")) {
            return 3;
        }

        if (value.contains("2160P") ||
                value.contains("3840X2160") ||
                value.contains("3840×2160") ||
                containsToken(value, "4K") ||
                containsToken(value, "UHD")) {
            return 2;
        }

        if (value.contains("1440P") ||
                value.contains("2560X1440") ||
                value.contains("2560×1440") ||
                containsToken(value, "2K") ||
                containsToken(value, "QHD")) {
            return 1;
        }

        return 0;
    }

    private static boolean containsToken(String source, String token) {
        int index = source.indexOf(token);

        while (index >= 0) {
            int before = index - 1;
            int after = index + token.length();

            boolean cleanBefore =
                    before < 0 ||
                    !Character.isLetterOrDigit(source.charAt(before));

            boolean cleanAfter =
                    after >= source.length() ||
                    !Character.isLetterOrDigit(source.charAt(after));

            if (cleanBefore && cleanAfter) {
                return true;
            }

            index = source.indexOf(token, index + 1);
        }

        return false;
    }

    private static final class QualityState {
        private int rank;

        void acceptHeight(int height) {
            if (height >= 4320) {
                rank = Math.max(rank, 3);
            } else if (height >= 2160) {
                rank = Math.max(rank, 2);
            } else if (height >= 1440) {
                rank = Math.max(rank, 1);
            }
        }

        void acceptText(String text) {
            rank = Math.max(
                    rank,
                    detectTextRank(text)
            );
        }

        String toLabel() {
            switch (rank) {
                case 3:
                    return "8K";

                case 2:
                    return "4K";

                case 1:
                    return "2K";

                default:
                    return null;
            }
        }
    }
}
