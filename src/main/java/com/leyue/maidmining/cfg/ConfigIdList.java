package com.leyue.maidmining.cfg;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraftforge.registries.IForgeRegistry;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 把配置里的字符串列表解析成注册表对象集合（方块 / 物品通用）。
 * <p>
 * <b>为什么单独抽一个类</b>：1.0.2 的建材白名单在 {@link MiningConfig} 里手写了一份
 * "解析方块"的逻辑，1.1.0 又要给工具和寻找目标各来一份。三份几乎相同的代码
 * 迟早会漂移（比如有人给其中一份加了通配符，另两份没加），所以统一到这里，
 * 只保留"字符串 → 对象"这一件事。
 * <p>
 * <b>支持的四种写法</b>：
 * <ol>
 *   <li><b>标签</b>：{@code #forge:ores} —— 展开成该标签当前的全部成员。
 *       注意标签是在<b>解析时</b>展开的，所以数据包重载后必须让缓存失效（见
 *       {@link MiningConfig#invalidateCaches()}）；</li>
 *   <li><b>命名空间通配</b>：{@code mekanism:*} —— 该 mod 的全部方块/物品。</li>
 *   <li><b>路径通配</b>：{@code minecraft:*_ore}、{@code somemod:*_pickaxe}、
 *       {@code minecraft:raw_*} —— 按名字前缀/后缀批量匹配。给"所有模组矿石"
 *       这类需求用，免得逐个抄注册名；</li>
 *   <li><b>注册名</b>：{@code minecraft:gravel}。</li>
 * </ol>
 * 通配只认 {@code *} 一个元字符（匹配任意长度，含空串），其余字符按字面处理——
 * 配置是玩家手写的，不该要求他们懂正则。
 * <p>
 * <b>无效条目一律静默跳过</b>，不抛异常也不打日志：写错一个 id 不该让服务器起不来。
 * 但"写过内容"这一点由调用方另行判断（{@link MiningConfig#toolWhitelistConfigured()}），
 * 所以拼错全部条目会表现为"没工具可用"，而不是悄悄退回默认值。
 */
public final class ConfigIdList {

    private ConfigIdList() {
    }

    /**
     * 解析一批条目。
     *
     * @param registry 目标注册表（{@code ForgeRegistries.BLOCKS} / {@code ITEMS}）
     * @param entries  配置读出来的原始列表
     * @return 解析结果；没有任何有效条目时返回空集合（调用方据此判断"未配置"）
     */
    public static <T> Set<T> resolve(IForgeRegistry<T> registry, List<? extends Object> entries) {
        Set<T> result = new HashSet<>();
        ResourceKey<? extends Registry<T>> registryKey = registry.getRegistryKey();
        for (Object raw : entries) {
            if (!(raw instanceof String text)) {
                continue;
            }
            String entry = text.trim();
            if (entry.isEmpty()) {
                continue;
            }
            if (entry.charAt(0) == '#') {
                addTag(registry, registryKey, result, entry.substring(1));
            } else if (entry.indexOf('*') >= 0) {
                addWildcard(registry, result, entry);
            } else {
                addId(registry, result, entry);
            }
        }
        return result;
    }

    /** {@code #ns:path} → 标签成员。标签不存在时 Forge 返回空集合，不是 null。 */
    private static <T> void addTag(IForgeRegistry<T> registry,
                                   ResourceKey<? extends Registry<T>> registryKey,
                                   Set<T> result, String tagText) {
        ResourceLocation tagId = ResourceLocation.tryParse(tagText);
        if (tagId == null) {
            return;
        }
        registry.tags().getTag(TagKey.create(registryKey, tagId)).forEach(result::add);
    }

    /**
     * {@code ns:path} 里带 {@code *} 的条目 → 遍历该命名空间按通配匹配。
     * <p>
     * 没有冒号（例如只写 {@code *})时视为"任意命名空间"，方便一次性放开全部。
     */
    private static <T> void addWildcard(IForgeRegistry<T> registry, Set<T> result, String pattern) {
        int colon = pattern.indexOf(':');
        String namespace = colon < 0 ? "" : pattern.substring(0, colon);
        String pathPattern = colon < 0 ? pattern : pattern.substring(colon + 1);
        Pattern regex = Pattern.compile(globToRegex(pathPattern));
        for (ResourceLocation key : registry.getKeys()) {
            if (!namespace.isEmpty() && !key.getNamespace().equals(namespace)) {
                continue;
            }
            if (!regex.matcher(key.getPath()).matches()) {
                continue;
            }
            T value = registry.getValue(key);
            if (value != null) {
                result.add(value);
            }
        }
    }

    /** {@code ns:path} → 单个对象。 */
    private static <T> void addId(IForgeRegistry<T> registry, Set<T> result, String idText) {
        ResourceLocation id = ResourceLocation.tryParse(idText);
        if (id == null || !registry.containsKey(id)) {
            return;
        }
        T value = registry.getValue(id);
        if (value != null) {
            result.add(value);
        }
    }

    /**
     * 把只含 {@code *} 的通配转成正则。
     * <p>
     * 逐字符转义而不是先 {@code Pattern.quote} 再替换：后者会把 {@code *} 也一起转义，
     * 还得再反转义回来，容易写错。
     */
    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if ("\\.[]{}()+-^$|?".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        return regex.toString();
    }
}
