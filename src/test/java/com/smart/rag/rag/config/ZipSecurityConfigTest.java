package com.smart.rag.rag.config;

import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockStatic;

/**
 * R2-H2: ZipSecurityConfig 启动期阈值钉死与回读断言测试。
 * <p>
 * 验证 {@code pinZipSecurityDefaults()} 的 fail-fast 行为：当
 * {@link ZipSecureFile} 的任一阈值被外部（系统属性 / 反射 / POI 版本漂移）
 * 削弱为与安全默认值不一致时，必须在启动期抛出 {@link IllegalStateException}
 * 而非静默降级。
 * <p>
 * 由于 {@code pinZipSecurityDefaults()} 先 set 再 read-back，直接篡改静态字段无法
 * 触发断言（pin 会重新设回正确值）。因此用 {@link MockedStatic} 模拟「POI 版本漂移
 * 导致 setter 静默失效」：setter 为 no-op，getter 返回被削弱的值，使 read-back
 * 断言命中 fail-fast 路径。
 */
@DisplayName("ZipSecurityConfig — R2-H2 zip-bomb 阈值钉死与回读断言")
class ZipSecurityConfigTest {

    private static final double SECURE_RATIO = 0.01;
    private static final long SECURE_ENTRY = 4_294_967_295L;
    private static final long SECURE_TEXT = 10_000_000L;

    /**
     * 通过反射调用包级 {@code pinZipSecurityDefaults()}，避免依赖 Spring 生命周期。
     * {@link Method#invoke} 会把目标异常包装为 InvocationTargetException，断言需检查 cause。
     */
    private void invokePin(ZipSecurityConfig config) throws Exception {
        Method pin = ZipSecurityConfig.class.getDeclaredMethod("pinZipSecurityDefaults");
        pin.setAccessible(true);
        pin.invoke(config);
    }

    @Test
    @DisplayName("默认场景：阈值未被外部篡改 → pin 成功，不抛异常")
    void pinSucceeds_whenThresholdsMatchDefaults() throws Exception {
        ZipSecurityConfig config = new ZipSecurityConfig();
        assertThatCode(() -> invokePin(config))
                .as("阈值与安全默认值一致时 pin 必须成功")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("minInflateRatio setter 静默失效（POI 漂移）→ pin 必须 fail-fast 抛 IllegalStateException")
    void pinThrows_whenMinInflateRatioWeakened() {
        // 模拟：setter 是 no-op（POI 版本漂移忽略调用），getter 返回被削弱的宽松值
        try (MockedStatic<ZipSecureFile> mocked = mockStatic(ZipSecureFile.class)) {
            mocked.when(() -> ZipSecureFile.setMinInflateRatio(org.mockito.ArgumentMatchers.anyDouble()))
                    .thenAnswer(inv -> null); // no-op
            mocked.when(ZipSecureFile::getMinInflateRatio).thenReturn(0.5); // 被削弱
            mocked.when(() -> ZipSecureFile.setMaxEntrySize(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> null);
            mocked.when(ZipSecureFile::getMaxEntrySize).thenReturn(SECURE_ENTRY);
            mocked.when(() -> ZipSecureFile.setMaxTextSize(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> null);
            mocked.when(ZipSecureFile::getMaxTextSize).thenReturn(SECURE_TEXT);

            assertThatThrownBy(() -> invokePin(new ZipSecurityConfig()))
                    .as("minInflateRatio setter 失效时必须 fail-fast")
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("maxEntrySize setter 静默失效（POI 漂移）→ pin 必须 fail-fast")
    void pinThrows_whenMaxEntrySizeWeakened() {
        try (MockedStatic<ZipSecureFile> mocked = mockStatic(ZipSecureFile.class)) {
            mocked.when(() -> ZipSecureFile.setMinInflateRatio(org.mockito.ArgumentMatchers.anyDouble()))
                    .thenAnswer(inv -> null);
            mocked.when(ZipSecureFile::getMinInflateRatio).thenReturn(SECURE_RATIO);
            mocked.when(() -> ZipSecureFile.setMaxEntrySize(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> null); // no-op
            mocked.when(ZipSecureFile::getMaxEntrySize).thenReturn(Long.MAX_VALUE); // 被削弱
            mocked.when(() -> ZipSecureFile.setMaxTextSize(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> null);
            mocked.when(ZipSecureFile::getMaxTextSize).thenReturn(SECURE_TEXT);

            assertThatThrownBy(() -> invokePin(new ZipSecurityConfig()))
                    .as("maxEntrySize setter 失效时必须 fail-fast")
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    @DisplayName("maxTextSize setter 静默失效（POI 漂移）→ pin 必须 fail-fast")
    void pinThrows_whenMaxTextSizeWeakened() {
        try (MockedStatic<ZipSecureFile> mocked = mockStatic(ZipSecureFile.class)) {
            mocked.when(() -> ZipSecureFile.setMinInflateRatio(org.mockito.ArgumentMatchers.anyDouble()))
                    .thenAnswer(inv -> null);
            mocked.when(ZipSecureFile::getMinInflateRatio).thenReturn(SECURE_RATIO);
            mocked.when(() -> ZipSecureFile.setMaxEntrySize(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> null);
            mocked.when(ZipSecureFile::getMaxEntrySize).thenReturn(SECURE_ENTRY);
            mocked.when(() -> ZipSecureFile.setMaxTextSize(org.mockito.ArgumentMatchers.anyLong()))
                    .thenAnswer(inv -> null); // no-op
            mocked.when(ZipSecureFile::getMaxTextSize).thenReturn(Long.MAX_VALUE); // 被削弱

            assertThatThrownBy(() -> invokePin(new ZipSecurityConfig()))
                    .as("maxTextSize setter 失效时必须 fail-fast")
                    .hasCauseInstanceOf(IllegalStateException.class);
        }
    }
}
