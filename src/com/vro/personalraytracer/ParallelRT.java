package com.vro.personalraytracer;

import com.vro.personalraytracer.materials.BlinnPhongShading;
import com.vro.personalraytracer.objects.Camera;
import com.vro.personalraytracer.objects.Object3D;
import com.vro.personalraytracer.objects.lights.DirectionalLight;
import com.vro.personalraytracer.objects.lights.Light;
import com.vro.personalraytracer.objects.lights.PointLight;
import com.vro.personalraytracer.tools.*;

import java.awt.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ParallelRT {
    public static final String renderPath = "src/com/vro/personalraytracer/renders/";
    public static BufferedImage render;

    public static void main(String[] args) {

        System.out.println(new Date());

        Scene selectedScene = sceneManager();
        render = new BufferedImage(selectedScene.getCamera().getWidth(), selectedScene.getCamera().getHeight(), BufferedImage.TYPE_INT_RGB);

        parallelMethod(selectedScene);

        File outputImage = new File(renderPath + "bowlingblinnPhong.png");
        try {
            ImageIO.write(render, "png", outputImage);
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println(new Date());
    }

    public static void parallelMethod(Scene scene) {
        Camera mainCamera = scene.getCamera();
        double[] nearFarPlanes = mainCamera.getNearFarPlanes();
        List<Object3D> objects = scene.getObjects();
        List<Light> lights = scene.getLights();

        Vector3D[][] positionsToRaytrace = mainCamera.calculatePositionsToRay();

        ExecutorService executorService = Executors.newFixedThreadPool(6);
        for (int x = 0; x < render.getWidth(); x++) {
            for (int y = 0; y < render.getHeight(); y++) {
                Runnable runnable = raytrace(x, y, mainCamera, nearFarPlanes, objects, lights, positionsToRaytrace);
                executorService.execute(runnable);
            }
        }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, TimeUnit.MINUTES)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if (!executorService.isTerminated()) {
                System.err.println("Cancel non-finished");
            }
        }
        executorService.shutdownNow();
    }

    public static Runnable raytrace(int i, int j, Camera mainCamera, double[] nearFarPlanes, List<Object3D> objects, List<Light> lights, Vector3D[][] positionsToRaytrace) {
        return () -> {
            double x = positionsToRaytrace[i][j].getX() + mainCamera.getPosition().getX();
            double y = positionsToRaytrace[i][j].getY() + mainCamera.getPosition().getY();
            double z = positionsToRaytrace[i][j].getZ() + mainCamera.getPosition().getZ();

            Ray ray = new Ray(mainCamera.getPosition(), new Vector3D(x, y, z));
            Intersection closestIntersection = raycast(ray, objects, null, new double[]{nearFarPlanes[0], nearFarPlanes[1]});

            Color pixelColor = Color.BLACK;
            if (closestIntersection != null) {
                Color objColor = closestIntersection.getObject().getColor();

                boolean insideShadow = false;
                for (Light light : lights) {
                    double nDotL = light.getNDotL(closestIntersection);
                    double intensity = light.getIntensity() * nDotL;
                    double lightDistance = Vector3D.vectorSubstraction(light.getPosition(), closestIntersection.getPosition()).getMagnitude();
                    Color lightColor = light.getColor();
                    double lightFallOff = (intensity / Math.pow(lightDistance, 2));
                    double[] lightColors = new double[]{lightColor.getRed() / 255.0, lightColor.getGreen() / 255.0, lightColor.getBlue() / 255.0};
                    double[] objColors = new double[]{objColor.getRed() / 255.0, objColor.getGreen() / 255.0, objColor.getBlue() / 255.0};

                    for (Object3D object : objects) {
                        if (!object.equals(closestIntersection.getObject())) {
                                Ray shadowRay = new Ray(closestIntersection.getPosition(), Vector3D.normalize(Vector3D.vectorSubstraction(light.getPosition(), closestIntersection.getPosition())));
                                Intersection shadowIntersection = object.getIntersection(shadowRay);
                            if (shadowIntersection != null && shadowIntersection.getDistance() > 0 && shadowIntersection.getDistance() < closestIntersection.getDistance()) {
                                insideShadow = true;
                                break;
                            }
                        }
                    }

                    if (!insideShadow) {
                        for (int colorIndex = 0; colorIndex < objColors.length; colorIndex++) {
                            objColors[colorIndex] *= lightFallOff * ColorsHandler.addColor(ColorsHandler.addColor(BlinnPhongShading.getAmbient(closestIntersection, 0.02), BlinnPhongShading.getDiffuse(closestIntersection, light, 0.25)), BlinnPhongShading.getSpecular(closestIntersection, mainCamera.getPosition(), light, 0.75, 100)).getRed() * lightColors[colorIndex];
                        }

                        Color diffuse = new Color(ColorsHandler.clamp(objColors[0], 0, 1), ColorsHandler.clamp(objColors[1], 0, 1), ColorsHandler.clamp(objColors[2], 0, 1));
                        pixelColor = ColorsHandler.addColor(pixelColor, diffuse);
                    }
                }
            }

            setRGB(i, j, pixelColor.getRGB());
        };
    }

    public static synchronized void setRGB(int x, int y, int pixelColor) {
        render.setRGB(x, y, pixelColor);
    }

    /**
     * Raycast intersection.
     *
     * @param ray            the ray
     * @param objects        the objects
     * @param caster         the caster
     * @param clippingPlanes the clipping planes
     * @return the intersection
     */
    public static Intersection raycast(Ray ray, List<Object3D> objects, Object3D caster, double[] clippingPlanes) {
        Intersection closestIntersection = null;

        for (Object3D currentObj : objects) {
            if (caster == null || !currentObj.equals(caster)) {
                Intersection intersection = currentObj.getIntersection(ray);
                if (intersection != null) {
                    double distance = intersection.getDistance();
                    double intersectionZ = intersection.getPosition().getZ();
                    if (distance >= 0 &&
                            (closestIntersection == null || distance < closestIntersection.getDistance()) &&
                            (clippingPlanes == null || (intersectionZ >= clippingPlanes[0] && intersectionZ <= clippingPlanes[1]))) {
                        closestIntersection = intersection;
                    }
                }
            }
        }

        return closestIntersection;
    }

    /**
     * Scene manager scene.
     *
     * @return the scene
     */
    public static Scene sceneManager() {
        Scene finalScene;

        Scene scene01 = new Scene();
        scene01.setCamera(new Camera(new Vector3D(0, 0, -4), 640, 360, 90, 60, 0.6, 50.0));
//        scene01.addLight(new DirectionalLight(new Vector3D(0, 1, 0), new Vector3D(0, 1, 1), Color.WHITE, 1));
        scene01.addLight(new PointLight(new Vector3D(4, 3, 7), Color.WHITE, 0.4));
        scene01.addLight(new PointLight(new Vector3D(-4, 3, 7), Color.WHITE, 0.4));
        scene01.addObject(OBJReader.getModel3D("BowlingFloor.obj", new Vector3D(0, -2, 0), new Vector3D(1, 1, 1), new Color(180, 100, 45)));
        scene01.addObject(OBJReader.getModel3D("BowlingWall.obj", new Vector3D(0, -2, 12.5), new Vector3D(1, 1, 1), new Color(11, 1, 74)));
        scene01.addObject(OBJReader.getModel3D("BowlingBall.obj", new Vector3D(1.25, -2, 2.5), new Vector3D(1, 1, 1), Color.WHITE));
        scene01.addObject(OBJReader.getModel3D("BowlingPin.obj", new Vector3D(0, -3, 10), new Vector3D(1, 1, 1), Color.WHITE));
        Scene scene02 = new Scene();
        scene02.setCamera(new Camera(new Vector3D(0, 0, -4), 300, 300, 90, 90, 0.6, 50.0));
//        scene02.addLight(new PointLight(new Vector3D(0, -10, 5.5), Color.WHITE, 20));
        scene02.addLight(new PointLight(new Vector3D(0, -10, 5.5), Color.WHITE, 20));
//        scene02.addLight(new DirectionalLight(new Vector3D(0, -10, 0), new Vector3D(0, -10, 5.5), Color.WHITE, 30));
        scene02.addObject(OBJReader.getModel3D("Cube.obj", new Vector3D(0, -0.5, 6), new Vector3D(1, 1, 1), Color.BLUE));
        scene02.addObject(OBJReader.getModel3D("Floor.obj", new Vector3D(0, -1, 6), new Vector3D(1.5, 1.5, 1.5), Color.YELLOW));
        scene02.addObject(OBJReader.getModel3D("Container.obj", new Vector3D(0, -4, 5), new Vector3D(1.5, 1.5, 1.5), Color.DARK_GRAY));
        scene02.addObject(OBJReader.getModel3D("BowlingPin.obj", new Vector3D(0, -8, 7), new Vector3D(1, 1, 1), Color.GREEN));

        Scene scene03 = new Scene();
        scene03.setCamera(new Camera(new Vector3D(0, 0, -4), 400, 400, 60, 60, 0.6, 50.0));
        scene03.addLight(new PointLight(new Vector3D(0, 2, 3.5), Color.WHITE, 10));
        scene03.addLight(new PointLight(new Vector3D(0, -1, 2), Color.WHITE, 10));
        scene03.addObject(OBJReader.getModel3D("Floor.obj", new Vector3D(0, -2, 5), new Vector3D(1.5, 1.5, 1.5), Color.YELLOW));
        scene03.addObject(OBJReader.getModel3D("BowlingBall.obj", new Vector3D(0, -2, 5), new Vector3D(0.5, 0.5, 0.5), Color.BLUE));


        finalScene = scene01;
        return finalScene;
    }
}
