package com.ukulele.monitor;

enum GuideScene {
    CLASSIC("classic","滚动跟弹","熟悉的横向方块与时间刻度"),
    STARS("stars","星河跟弹","光点沿星轨移动，尾迹表示音长"),
    ENSEMBLE("ensemble","精灵乐队","四位角色各守一根弦，音符到达时亮起"),
    TIDE("tide","海月潮汐","音符化作浮灯，在潮线处提示拨弦"),
    CANDY("candy","糖豆派对","四个软糖角色在落点吞下品位糖豆");

    final String id,label,detail;
    GuideScene(String id,String label,String detail){this.id=id;this.label=label;this.detail=detail;}
    static GuideScene byId(String id){
        for(GuideScene scene:values())if(scene.id.equals(id))return scene;
        return CLASSIC;
    }
}
