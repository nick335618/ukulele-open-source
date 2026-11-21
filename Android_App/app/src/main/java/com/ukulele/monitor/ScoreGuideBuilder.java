package com.ukulele.monitor;

/** Direct recognized/corrected text -> device guide, while keeping the PDF/photo original. */
final class ScoreGuideBuilder {
    static final class Built {
        final ScoreDraft.Parsed draft;
        final ScorePitch.Result mapped;
        final SongTimeline timeline;
        Built(String text){draft=ScoreDraft.parse(text);mapped=ScorePitch.convert(draft.notes,draft.durationMs);timeline=SongTimeline.parse(mapped.events);if(timeline.noteCount==0)throw new IllegalArgumentException("没有可演奏音符，请校对音高或移调");}
    }
    static Built build(String text){return new Built(text);}
    static Built save(ScoreRepository repo,String id,String text)throws Exception{
        Built result=build(text);ScoreRepository.Item item=repo.get(id);item.type="识谱指引";
        ScoreRepository.atomic(new java.io.File(repo.folder(id),"draft.txt"),text.getBytes("UTF-8"));
        repo.setEvents(item,result.mapped.events,result.mapped.warning(),result.mapped.outside+result.mapped.overflow);return result;
    }
}
