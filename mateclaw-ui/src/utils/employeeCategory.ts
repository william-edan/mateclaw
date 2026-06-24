/**
 * 数字员工的「标签 / 分类」筛选。
 *
 * 启动时由 DigitalEmployeeSeedService 播种的内置员工，其 `tags` 就是下面 6 个业务
 * 分类之一（单个标签）。前端据此把员工分成「内置 + 各分类」做筛选，无需后端加字段：
 *   - builtin：tags 命中这 6 个分类中的任意一个（即系统内置员工）
 *   - 某个分类：tags 正好是该分类
 *   - all：不筛选
 *
 * 用户自建员工的 tags 通常为空或自定义文案，因此默认 `builtin` 视图只会显示内置员工。
 */

export const EMPLOYEE_CATEGORIES = [
  '内容生成',
  '游戏/漫剧',
  '销售/售前',
  '品牌设计',
  '运营优化',
  'OPC',
] as const

export interface EmployeeFilterTab {
  /** 传给筛选逻辑的值；分类项即分类原文（与库里的 tags 对应）。 */
  value: string
  /** tab 显示文案。 */
  label: string
}

/** 筛选 tab 顺序：内置（默认）→ 各分类 → 全部。 */
export const EMPLOYEE_FILTER_TABS: EmployeeFilterTab[] = [
  { value: 'builtin', label: '内置' },
  ...EMPLOYEE_CATEGORIES.map((c) => ({ value: c, label: c })),
  { value: 'all', label: '全部' },
]

/** 默认选中的筛选值。 */
export const DEFAULT_EMPLOYEE_FILTER = 'builtin'

/** 取员工的主分类 = tags 的第一个非空标签。 */
export function primaryCategory(tags?: string | null): string {
  return (tags || '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean)[0] || ''
}

/** 员工筛选用的最小字段集。 */
export interface EmployeeLike {
  builtin?: boolean | null
  tags?: string | null
}

/**
 * 判断某员工是否匹配给定筛选值：
 *  - builtin：看后端 builtin 字段（V146，内置 = admin / 系统创建）
 *  - 某分类：看 tags 的主分类
 *  - all：全部
 */
export function matchesEmployeeCategory(agent: EmployeeLike, filter: string): boolean {
  if (!filter || filter === 'all') return true
  if (filter === 'builtin') return agent?.builtin === true
  return primaryCategory(agent?.tags) === filter
}
