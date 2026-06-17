// Barrel re-exports for the per-kind ActionExecutor handlers.
//
// All six handler factories landed via Phase 2 Wave 1 + Wave 2; the SW
// composes them in `sw/index.ts`. This barrel exists so future call sites
// (e.g. a future ActionExecutor factory in tests) can import a single
// symbol rather than six.

export { navigateHandler }   from './navigate'
export { clickHandler }      from './click'
export { typeHandler }       from './type'
export { pressKeyHandler }   from './press_key'
export { scrollHandler }     from './scroll'
export { scrollRegionHandler } from './scroll_region'
export { registerRegionHandler } from './register_region'
export { detectRegionHandler } from './detect_region'
export { extractRegionHandler } from './extract_region'
export { openAuthorFromCommentHandler } from './open_author_from_comment'
export { clickProfileActionHandler } from './click_profile_action'
export { typeDmDraftHandler } from './type_dm_draft'
export { closeTabHandler } from './close_tab'
export { moveMouseHandler }  from './move_mouse'
export { waitHandler }       from './wait'
export { douyinCommentNetworkHandler } from './douyin_comment_network'
export { douyinSearchHandler } from './douyin_search'
export { douyinOpenVideoHandler } from './douyin_open_video'
export { douyinUiHandler } from './douyin_ui'
