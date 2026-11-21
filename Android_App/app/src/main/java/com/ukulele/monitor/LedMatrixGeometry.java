package com.ukulele.monitor;

/** Shared drawing/hit-test geometry. Logical order remains G/C/E/A × 18. */
final class LedMatrixGeometry {
    final float left, top, cellW, cellH;
    final boolean horizontal;
    LedMatrixGeometry(float width, float height, float density) {
        horizontal = width > height * 1.5f;
        left = (horizontal ? 42 : 30) * density;
        top = 28 * density;
        cellW = Math.max(1, (width-left-12*density)/(horizontal?18:4));
        cellH = Math.max(1, (height-top-10*density)/(horizontal?4:18));
    }
    float x(int i) { return left + ((horizontal?i%18:i/18)+.5f)*cellW; }
    float y(int i) { return top + ((horizontal?i/18:i%18)+.5f)*cellH; }
    int hit(float x,float y) {
        int col=(int)Math.floor((x-left)/cellW), row=(int)Math.floor((y-top)/cellH);
        if(x<left||y<top||col<0||row<0||col>=(horizontal?18:4)||row>=(horizontal?4:18))return -1;
        return horizontal?row*18+col:col*18+row;
    }
    int stringHeader(float x,float y) {
        if(horizontal && x<left && y>=top) {int n=(int)((y-top)/cellH);return n<4?n:-1;}
        if(!horizontal && y<top && x>=left) {int n=(int)((x-left)/cellW);return n<4?n:-1;}
        return -1;
    }
    int fretHeader(float x,float y) {
        if(horizontal && y<top && x>=left) {int n=(int)((x-left)/cellW);return n<18?n:-1;}
        if(!horizontal && x<left && y>=top) {int n=(int)((y-top)/cellH);return n<18?n:-1;}
        return -1;
    }
}
