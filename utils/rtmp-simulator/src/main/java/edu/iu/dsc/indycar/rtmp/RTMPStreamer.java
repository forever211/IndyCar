package edu.iu.dsc.indycar.rtmp;

import com.sun.xml.internal.ws.api.message.Packet;
import com.xuggle.mediatool.IMediaReader;
import com.xuggle.mediatool.MediaListenerAdapter;
import com.xuggle.mediatool.ToolFactory;
import com.xuggle.mediatool.event.IVideoPictureEvent;
import com.xuggle.xuggler.*;
import com.xuggle.xuggler.video.ConverterFactory;
import com.xuggle.xuggler.video.IConverter;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class RTMPStreamer {

  //private static String url = "rtmp://localhost/live/";
  private static String url = "rtmp://live.restream.io/live/";
  private static String fileName = "re_1865254_207e6392653ed8d4e140";
  private static int height = 720;
  private static int width = 1280;

  public static void main(String[] args) {
    final IContainer container = IContainer.make();
    IContainerFormat containerFormat_live = IContainerFormat.make();
    containerFormat_live.setOutputFormat("flv", url + fileName, null);
    container.setInputBufferLength(10);
    int retVal = container.open(url + fileName, IContainer.Type.WRITE, containerFormat_live);
    if (retVal < 0) {
      System.err.println("Could not open output container for live stream");
      System.exit(1);
    }
    IStream stream = container.addNewStream(0);
    final IStreamCoder coder = stream.getStreamCoder();
    final ICodec codec = ICodec.findEncodingCodec(ICodec.ID.CODEC_ID_H264);
    coder.setNumPicturesInGroupOfPictures(30);
    coder.setCodec(codec);
    coder.setBitRate(1653 * 1000);
    coder.setPixelType(IPixelFormat.Type.YUV420P);
    coder.setHeight(height);
    coder.setWidth(width);
    System.out.println("[ENCODER] video size is " + width + "x" + height);
    coder.setFlag(IStreamCoder.Flags.FLAG_QSCALE, true);
    coder.setGlobalQuality(0);
    IRational frameRate = IRational.make(30, 1);
    coder.setFrameRate(frameRate);
    coder.setTimeBase(IRational.make(frameRate.getDenominator(), frameRate.getNumerator()));
    Properties props = new Properties();
    InputStream is = RTMPStreamer.class.getResourceAsStream("/libx264-normal.ffpreset");
    try {
      props.load(is);
    } catch (IOException e) {
      System.err.println("You need the libx264-normal.ffpreset file from the Xuggle distribution in your classpath.");
      System.exit(1);
    }
    Configuration.configure(props, coder);
    coder.open();
    container.writeHeader();
    final AtomicLong i = new AtomicLong(0);
    final long fisrtTimeStamp = System.currentTimeMillis();
    loopVideoRead(args[0], new MediaListenerAdapter() {
      @Override
      public void onVideoPicture(IVideoPictureEvent event) {
        BufferedImage image = event.getImage();
        IConverter converter = ConverterFactory.createConverter(image, IPixelFormat.Type.YUV420P);

        long timeStamp = (System.currentTimeMillis() - fisrtTimeStamp) * 1000;
        IVideoPicture picture = converter.toPicture(image, timeStamp);

        if (i.getAndIncrement() == 0 || i.get() % 40 == 0) {
          //make first frame keyframe
          picture.setKeyFrame(true);
        }

        picture.setQuality(0);
        IPacket packet = IPacket.make();
        coder.encodeVideo(packet, picture, 102400);
        picture.delete();
        if (packet.isComplete()) {
          container.writePacket(packet);
          if (i.get() % 10 == 0) {
            System.out.println("Frame rate : " + (i.get() * 1000 / (System.currentTimeMillis() - fisrtTimeStamp)));
          }
        } else {
          container.writePacket(packet);
        }
      }
    });
  }

  public static void loopVideoRead(final String sourceUrl, final MediaListenerAdapter mediaListenerAdapter) {

    new Thread(() -> {
      while (true) {
        IMediaReader reader = ToolFactory.makeReader(sourceUrl);
        reader.setBufferedImageTypeToGenerate(BufferedImage.TYPE_3BYTE_BGR);
        reader.addListener(mediaListenerAdapter);
        while (true) {
          long t1 = System.currentTimeMillis();
          IError iError = reader.readPacket();
          long t2 = System.currentTimeMillis();
          if (iError != null) {
            break;
          }
          try {
            long sleepOffSet = Math.max(0, (1000 / 30) - (t2 - t1));
            //Thread.sleep(sleepOffSet/3);
            Thread.sleep(0);
          } catch (InterruptedException e) {
            e.printStackTrace();
          }
        }
        reader.close();
      }
    }).start();
  }
}