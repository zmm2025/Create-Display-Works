package com.zmm2025.createdisplayworks.media;

import net.createmod.catnip.gui.element.ScreenElement;

/** Sixteen-pixel silhouettes, matching Create's white GUI glyphs. */
final class MediaIcons {
    private MediaIcons(){}
    private static ScreenElement pixels(String... rows){return ink(0xFFF0F0F0,rows);}
    private static ScreenElement ink(int color,String... rows){return (g,x,y)->{
        for(int r=0;r<rows.length;r++)for(int c=0;c<rows[r].length();c++){
            char p=rows[r].charAt(c);if(p!=' ')g.fill(x+c,y+r,x+c+1,y+r+1,p=='#'?color:0xFF929292);
        }
    };}
    static final ScreenElement STRETCH=pixels(
        "                ",
        " #####    ##### ",
        " ##          ## ",
        " # #        # # ",
        " #  #      #  # ",
        " #   #    #   # ",
        "      ####      ",
        "      #  #      ",
        "      #  #      ",
        "      ####      ",
        " #   #    #   # ",
        " #  #      #  # ",
        " # #        # # ",
        " ##          ## ",
        " #####    ##### ",
        "                ");
    static final ScreenElement FIT=pixels(
        "                ",
        " ############## ",
        " #            # ",
        " #            # ",
        " #            # ",
        " #            # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " #            # ",
        " #            # ",
        " #            # ",
        " #            # ",
        " ############## ",
        "                ");
    static final ScreenElement FILL=pixels(
        "                ",
        " ############## ",
        " #            # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " # ########## # ",
        " #            # ",
        " ############## ",
        "                ");
    static final ScreenElement INFO=ink(0xFF575F50,
        "                ","     ######     ","   ##      ##   ","  #          #  ","  #    ##    #  "," #            # "," #    ###     # "," #     ##     # "," #     ##     # "," #     ##     # ","  #   ####   #  ","  #          #  ","   ##      ##   ","     ######     ");
    static final ScreenElement DITHER=(g,x,y)->{
        for(int yy=2;yy<14;yy+=2)for(int xx=2;xx<14;xx+=2)
            if((xx+yy)%4==0)g.fill(x+xx,y+yy,x+xx+1,y+yy+1,0xFFF0F0F0);
    };
    static final ScreenElement CLOCK=(g,x,y)->{
        for(int i=0;i<64;i++){
            double a=i*Math.PI/32;int xx=7+(int)Math.round(Math.cos(a)*5),yy=8+(int)Math.round(Math.sin(a)*5);
            g.fill(x+xx,y+yy,x+xx+1,y+yy+1,0xFFF0F0F0);
        }
        g.fill(x+7,y+5,x+8,y+9,0xFFF0F0F0);
        for(int i=0;i<=3;i++)g.fill(x+7+i,y+8+i/3,x+8+i,y+9+i/3,0xFFF0F0F0);
    };
}
