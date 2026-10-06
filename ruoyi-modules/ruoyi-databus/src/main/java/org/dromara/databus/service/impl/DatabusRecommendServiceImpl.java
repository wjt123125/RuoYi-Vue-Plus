package org.dromara.databus.service.impl;

import cn.hutool.core.util.IdUtil;
import lombok.RequiredArgsConstructor;
import org.dromara.common.core.utils.StringUtils;
import org.dromara.databus.domain.DatabusCmpRecommendSeed;
import org.dromara.databus.domain.DatabusCmpUsage;
import org.dromara.databus.domain.bo.CmpPickBo;
import org.dromara.databus.domain.vo.CmpRecommendVo;
import org.dromara.databus.mapper.DatabusCmpRecommendSeedMapper;
import org.dromara.databus.mapper.DatabusCmpUsageMapper;
import org.dromara.databus.service.IDatabusRecommendService;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 组件插入推荐服务实现。
 *
 * <p>融合公式：{@code 最终分 = min(100, 种子分(缺省40) + min(60, 15 * log2(1 + 真账次数)))}。
 * 对数曲线的意图：第一次真实选择就有明显抬升（+15），十几次后摸到 60 分天花板，
 * 防止高频组件靠绝对次数碾压语义更贴的种子推荐。
 *
 * @author databus
 */
@Service
@RequiredArgsConstructor
public class DatabusRecommendServiceImpl implements IDatabusRecommendService {

    /** 无前置时的锚点占位值，与种子表 ANY 行对应 */
    private static final String ANY_ANCHOR = "ANY";

    /** 真账有计数、种子未覆盖时的兜底基线分（对应前端本地规则的「其他」档） */
    private static final int DEFAULT_SEED_SCORE = 40;

    /** 真账抬升封顶，避免次数绝对碾压 */
    private static final int USAGE_BOOST_CAP = 60;

    /** 最终分封顶 */
    private static final int FINAL_SCORE_CAP = 100;

    /** 合法场景白名单，拒绝脏数据写入真账表 */
    private static final Set<String> VALID_SCENES = Set.of("prepend", "append", "replace", "insertEdge");

    private final DatabusCmpRecommendSeedMapper seedMapper;
    private final DatabusCmpUsageMapper usageMapper;

    @Override
    public List<CmpRecommendVo> recommend(String mode, String anchorType, List<String> excludedTypes) {
        String scene = VALID_SCENES.contains(mode) ? mode : "append";
        String anchor = StringUtils.isBlank(anchorType) ? ANY_ANCHOR : anchorType;
        Set<String> excluded = excludedTypes == null ? Set.of() : new HashSet<>(excludedTypes);

        // 种子：同一 pickedType 可能同时命中精确行与 ANY 回退行，取最高分
        Map<String, Integer> seedMap = new HashMap<>();
        for (DatabusCmpRecommendSeed seed : seedMapper.selectMatched(anchor, scene)) {
            seedMap.merge(seed.getPickedType(), seed.getScore(), Math::max);
        }

        // 真账：当前锚点 + 当前场景的精确计数
        Map<String, Integer> usageMap = new HashMap<>();
        for (DatabusCmpUsage usage : usageMapper.selectByAnchor(anchor, scene)) {
            usageMap.put(usage.getPickedType(), usage.getPickCount());
        }

        // 候选并集：只输出有信号的类型（种子命中或真账计数 > 0），其余类型前端本地规则兜底
        Set<String> candidates = new HashSet<>();
        candidates.addAll(seedMap.keySet());
        candidates.addAll(usageMap.keySet());

        return candidates.stream()
            .filter(type -> !excluded.contains(type))
            .map(type -> {
                int base = seedMap.getOrDefault(type, DEFAULT_SEED_SCORE);
                int count = usageMap.getOrDefault(type, 0);
                int boost = (int) Math.min(USAGE_BOOST_CAP, Math.round(15.0 * (Math.log(count + 1) / Math.log(2))));
                int score = Math.min(FINAL_SCORE_CAP, base + boost);
                return new CmpRecommendVo(type, score);
            })
            .sorted(Comparator.comparing(CmpRecommendVo::getScore).reversed()
                .thenComparing(CmpRecommendVo::getType))
            .toList();
    }

    @Override
    public void recordPick(CmpPickBo bo) {
        if (!VALID_SCENES.contains(bo.getMode()) || StringUtils.isBlank(bo.getPickedType())) {
            return;
        }
        String anchor = StringUtils.isBlank(bo.getAnchorType()) ? ANY_ANCHOR : bo.getAnchorType();
        usageMapper.upsertCount(IdUtil.getSnowflakeNextId(), anchor, bo.getPickedType(), bo.getMode());
    }

}
