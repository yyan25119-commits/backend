package com.nailglow.backend.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DoubaoImageService {
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    private final SystemSettingService systemSettingService;

    @Value("${nailglow.doubao.enabled:true}")
    private boolean enabled;

    @Value("${nailglow.doubao.api-key:}")
    private String apiKey;

    @Value("${nailglow.doubao.endpoint:}")
    private String endpoint;

    @Value("${nailglow.doubao.model:}")
    private String model;

    @Value("${nailglow.doubao.size:1024x1024}")
    private String size;

    public DoubaoImageService(SystemSettingService systemSettingService) {
        this.systemSettingService = systemSettingService;
    }

    public GenerationResult generate(String styleName, String styleDescription, String handImageDataUrl, String styleImageUrl) {
        String prompt = buildPrompt();
        String effectiveApiKey = systemSettingService.effectiveAiApiKey("doubao_api_key", apiKey, "DOUBAO_IMAGE_API_KEY");
        String effectiveModel = systemSettingService.getText("doubao_model", model);
        if (!enabled || effectiveApiKey == null || effectiveApiKey.isBlank()) {
            return GenerationResult.local(prompt, "未配置 DEEPSEEK_API_KEY / DOUBAO_API_KEY，已使用本地预览结果；配置后会自动调用豆包生图。");
        }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("model", effectiveModel);
            body.put("prompt", prompt);
            body.put("size", size);
            body.put("response_format", "url");
            List<String> referenceImages = new ArrayList<>();
            if (handImageDataUrl != null && !handImageDataUrl.isBlank()) {
                referenceImages.add(handImageDataUrl);
            }
            if (styleImageUrl != null && !styleImageUrl.isBlank()) {
                referenceImages.add(styleImageUrl);
            }
            if (referenceImages.size() == 1) {
                body.put("image", referenceImages.get(0));
            } else if (!referenceImages.isEmpty()) {
                body.put("image", referenceImages);
            }

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + effectiveApiKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return GenerationResult.local(prompt, "豆包生图接口返回 " + response.statusCode() + "，已保留任务并使用本地预览。");
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode first = root.path("data").isArray() && !root.path("data").isEmpty()
                    ? root.path("data").get(0)
                    : root.path("result");
            String url = first.path("url").asText("");
            String base64 = first.path("b64_json").asText("");
            if (!url.isBlank()) {
                return GenerationResult.remote(prompt, url);
            }
            if (!base64.isBlank()) {
                return GenerationResult.remote(prompt, "data:image/png;base64," + base64);
            }
            return GenerationResult.local(prompt, "豆包生图接口未返回图片地址，已使用本地预览。");
        } catch (Exception ex) {
            return GenerationResult.local(prompt, "豆包生图调用失败：" + ex.getMessage());
        }
    }

    private String buildPrompt() {
        return """
                任务：在用户自己的手上试戴美甲。最终图片必须看起来像“参考图 1 的这只手戴上了参考图 2 的美甲”。
                图片顺序：
                - 参考图 1 是最终成图的主体和画布，必须保留它的手部姿势、手掌朝向、手指位置、手指比例、肤色、光照、背景、裁切范围和相机角度。
                - 参考图 2 只是美甲款式素材，只能读取指甲区域的颜色、图案、装饰、光泽、质感和跳色/排列关系。

                关键要求：
                1. 成图构图必须跟参考图 1 一致，不能复制参考图 2 的手、手势、背景、彩虹光、阴影、皮肤、镜头角度或整体照片。
                2. 只在参考图 1 的指甲区域做编辑；除指甲外，参考图 1 的手和背景尽量保持原样。
                3. 将参考图 2 的美甲设计贴合到参考图 1 的每个指甲上，甲型、长度、弧度和角度必须适配参考图 1 的原始甲床。
                4. 不要根据款式名称、标签、文字说明、常见美甲审美或模型默认偏好自行改色、换图案、增删装饰。
                5. 如果参考图 2 是黑色星星款，参考图 1 的手上也必须呈现黑色星星款；如果参考图 2 不是红色，严禁生成红色或酒红色。
                6. 如果参考图 2 有跳色、单指图案、星星、钻饰、猫眼、法式边或渐变位置，按可见结构映射到参考图 1 的对应指甲。
                7. 不添加额外手指、文字、水印、首饰、夸张背景，不改变用户手的身份特征。

                生成要求：
                输出一张干净、真实、可用于用户确认“这款美甲在自己手上效果”的试戴图。第一优先级是保留参考图 1 的手和构图，第二优先级是让指甲款式与参考图 2 一致。
                """;
    }

    public record GenerationResult(boolean remote, String imageUrl, String prompt, String message) {
        static GenerationResult remote(String prompt, String imageUrl) {
            return new GenerationResult(true, imageUrl, prompt, "豆包生图完成");
        }

        static GenerationResult local(String prompt, String message) {
            return new GenerationResult(false, "", prompt, message);
        }
    }
}
