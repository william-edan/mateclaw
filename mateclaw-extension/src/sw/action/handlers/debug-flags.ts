/**
 * 临时调试开关集中处。
 *
 * 【DOM_ONLY_NO_CDP_FALLBACK】
 * 为 true 时,通用「鼠标点击 / 滚动」handler —— click / scroll / scroll_region ——
 * 在页内 DOM 路径失败后【不再回退 CDP】,而是直接抛 ActionFailureError 把失败暴露出来。
 *
 * 目的:在后台 / 最小化窗口下验证抖音获客主流程(排序 / 点视频 / 采集评论)是否真能
 * 纯 DOM 完成。开启后,哪一步 DOM 失败会立刻报错,而不是被 CDP 兜底悄悄掩盖,
 * 避免"明明想走 DOM、却感觉还是走了 CDP"的误判。
 *
 * 影响范围:仅 click / scroll / scroll_region(鼠标点击 + 滚动)。
 * 不影响打字(type)、按键(press_key)—— 所以私信、关 tab(Control+W)、
 * 搜索回车兜底等仍照常工作。
 *
 * 测试完把它改回 false 即可恢复全部 CDP 兜底。
 *
 * 注:显式标注 `: boolean`(而非让其推断成字面量 `true`),以免 TS 把 false 分支
 * 后续的 CDP 代码判定为 unreachable。
 */
export const DOM_ONLY_NO_CDP_FALLBACK: boolean = true
