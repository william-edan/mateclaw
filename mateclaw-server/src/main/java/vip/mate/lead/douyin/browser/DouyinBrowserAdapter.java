package vip.mate.lead.douyin.browser;

import vip.mate.lead.douyin.model.CommentCollectionResult;
import vip.mate.lead.douyin.model.DouyinCommentItem;
import vip.mate.lead.douyin.model.DouyinLeadAcquisitionInput;
import vip.mate.lead.douyin.model.EngagementResult;

import java.util.List;
import java.util.function.Consumer;

public interface DouyinBrowserAdapter {

    BrowserObservation openDouyinAndSearch(DouyinLeadAcquisitionInput input);

    BrowserObservation applySort(DouyinLeadAcquisitionInput input);

    BrowserObservation openVideo(int zeroBasedIndex);

    BrowserObservation openComments();

    RegionInfo detectCommentRegion();

    CommentCollectionResult collectAllComments(RegionInfo region);

    default CommentCollectionResult collectAllComments(RegionInfo region,
                                                       Consumer<CommentCollectionProgress> progressConsumer) {
        return collectAllComments(region);
    }

    BrowserObservation openAuthorProfile(DouyinCommentItem comment);

    EngagementResult followAndDraft(DouyinCommentItem comment, String dmDraft, boolean sendDm);

    record BrowserObservation(
            boolean ok,
            String url,
            String title,
            String tree,
            int viewportWidth,
            int viewportHeight,
            String code,
            String message
    ) {
        public static BrowserObservation failed(String code, String message) {
            return new BrowserObservation(false, "", "", "", 0, 0, code, message);
        }
    }

    record RegionInfo(
            String regionKey,
            double x,
            double y,
            double width,
            double height,
            double safeX,
            double safeY,
            String source
    ) {
        public static RegionInfo comments(double x, double y, double width, double height, String source) {
            return new RegionInfo(
                    "douyin.comments",
                    x,
                    y,
                    width,
                    height,
                    x + width / 2.0d,
                    y + Math.min(height - 8.0d, Math.max(8.0d, height / 2.0d)),
                    source == null || source.isBlank() ? "heuristic" : source);
        }
    }

    record ExtractedRegion(
            boolean ok,
            List<DouyinCommentItem> comments,
            int declaredCommentCount,
            boolean endReached,
            String code,
            String message
    ) {
        public ExtractedRegion {
            comments = comments == null ? List.of() : List.copyOf(comments);
        }
    }

    record CommentCollectionProgress(
            int commentsCollected,
            int declaredCommentCount,
            int scrollAttempts,
            int networkObservedPages,
            boolean networkHasMoreFalseObserved,
            String primaryCollectionSource,
            String stopReason
    ) {
        public CommentCollectionProgress {
            primaryCollectionSource = primaryCollectionSource == null ? "" : primaryCollectionSource;
            stopReason = stopReason == null ? "" : stopReason;
        }
    }
}
