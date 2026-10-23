package com.zmm2025.createdisplayworks.media;

import java.io.IOException;
import java.nio.file.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import com.github.weisj.jsvg.parser.*;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;

final class SvgRasterizer {
    static void convert(Path input,Path output) throws IOException {
        var context=LoaderContext.builder().externalResourcePolicy(ResourcePolicy.DENY_ALL).build();
        try(var stream=Files.newInputStream(input)) {
            var svg=new SVGLoader().load(stream,null,context);
            if(svg==null)throw new IOException("Cannot read this SVG.");
            double width=svg.size().width,height=svg.size().height;
            if(!Double.isFinite(width+height)||width<=0||height<=0)throw new IOException("SVG dimensions are invalid.");
            double factor=Math.min(1,1024/Math.max(width,height));
            var image=new BufferedImage(Math.max(1,(int)(width*factor)),Math.max(1,(int)(height*factor)),BufferedImage.TYPE_INT_ARGB);
            var graphics=image.createGraphics();
            try{graphics.scale(factor,factor);svg.render(null,graphics);}finally{graphics.dispose();}
            ImageIO.write(image,"png",output.toFile());
        }
    }
}
