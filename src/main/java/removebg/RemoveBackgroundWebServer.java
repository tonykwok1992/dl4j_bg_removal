package removebg;

import org.bytedeco.javacv.Java2DFrameUtils;
import org.bytedeco.opencv.opencv_core.Mat;
import org.datavec.image.loader.Java2DNativeImageLoader;
import org.nd4j.linalg.api.ndarray.INDArray;
import removebg.inpaint.SeamCarver;
import spark.Request;
import spark.Response;
import spark.Spark;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.bytedeco.opencv.global.opencv_photo.INPAINT_TELEA;
import static org.bytedeco.opencv.global.opencv_photo.inpaint;

public class RemoveBackgroundWebServer {

    private static final double INPUT_SIZE = 512.0d;
    private final BackgroundRemover b = BackgroundRemover.loadModel(System.getenv("MODEL_PATH"));

    public static void main(String[] args) {
        new RemoveBackgroundWebServer().start();
    }

    private void start() {
        Spark.port(5000);
        Spark.post("/removebg", this::inference);
        Spark.awaitInitialization();
        System.out.println("Started");
    }

    private Object inference(Request request, Response response) throws IOException {
        try {
            return doInference(request, response);
        } catch (Exception e) {
            e.printStackTrace();
            throw e;
        }
    }

    private Object doInference(Request request, Response response) throws IOException {
        byte[] body = request.bodyAsBytes();
        System.out.println("Received bytes with length " + body.length);
        long start = System.currentTimeMillis();
        try (InputStream bio = new ByteArrayInputStream(body)) {
            INDArray input = readStreamToBufferedImage(bio);
            INDArray mat = predict(input);
            BufferedImage bufferedImage = drawSegment(input, mat);
            response.raw().setContentType("image/png");
            try (OutputStream out = response.raw().getOutputStream()) {
                ImageIO.write(new SeamCarver(bufferedImage).carveImage(200,200), "png", out);
            }
        }
        System.out.println("Took " + (System.currentTimeMillis() - start) + "ms to finish all steps");
        return response;
    }

    private INDArray predict(INDArray input) {
        long start = System.currentTimeMillis();
        INDArray result = b.predict(input);
        System.out.println("Took " + (System.currentTimeMillis() - start) + "ms to finish predicting segment from model");
        return result;
    }

    private INDArray readStreamToBufferedImage(InputStream bio) throws IOException {
        long start = System.currentTimeMillis();
        BufferedImage bimg = ImageIO.read(bio);
        int width = bimg.getWidth();
        int height = bimg.getHeight();
        double resizeRatio = INPUT_SIZE / Math.max(width, height);
        while (resizeRatio > 1.0d) {
            resizeRatio /= 2;
        }
        Java2DNativeImageLoader l = new Java2DNativeImageLoader((int) (height * resizeRatio), (int) (width * resizeRatio), 3);
        INDArray indArray = l.asMatrix(bimg).permute(0, 2, 3, 1);
        System.out.println("Took " + (System.currentTimeMillis() - start) + "ms to finish predicting converting to image");
        return indArray;
    }


    private static BufferedImage drawSegment(INDArray baseImg, INDArray matImg) {
        long start = System.currentTimeMillis();

        long[] shape = baseImg.shape();

        long height = shape[1];
        long width = shape[2];
        BufferedImage image = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_3BYTE_BGR);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int red = baseImg.getInt(0, y, x, 2);
                int green = baseImg.getInt(0, y, x, 1);
                int blue = baseImg.getInt(0, y, x, 0);

                red = Math.max(Math.min(red, 255), 0);
                green = Math.max(Math.min(green, 255), 0);
                blue = Math.max(Math.min(blue, 255), 0);
                image.setRGB(x, y, new Color(red, green, blue).getRGB());
            }
        }

        BufferedImage maskImage = new BufferedImage((int) width, (int) height, BufferedImage.TYPE_BYTE_GRAY);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < height; y++) {
                int mask = matImg.getInt(0, y, x);
                if (mask != 0) {
                    maskImage.setRGB(x, y, new Color(255, 255, 255).getRGB());
                } else {
                    maskImage.setRGB(x, y, new Color(0, 0, 0).getRGB());
                }

            }
        }

        Mat maskMat = Java2DFrameUtils.toMat(maskImage);
        Mat imageMat = Java2DFrameUtils.toMat(image);
        inpaint(imageMat, maskMat, imageMat, 1.0d, INPAINT_TELEA);
        BufferedImage resultImage = Java2DFrameUtils.toBufferedImage(imageMat);
        System.out.println("Took " + (System.currentTimeMillis() - start) + "ms to finish drawing output image");
        return resultImage;
    }

}