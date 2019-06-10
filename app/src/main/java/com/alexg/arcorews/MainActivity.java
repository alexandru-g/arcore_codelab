package com.alexg.arcorews;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.net.Uri;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.google.android.material.snackbar.Snackbar;
import com.google.ar.core.Anchor;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Quaternion;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.math.Vector3Evaluator;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Light;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.PlaneRenderer;
import com.google.ar.sceneform.rendering.Texture;
import com.google.ar.sceneform.rendering.ViewRenderable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

public class MainActivity extends AppCompatActivity {

    private ArSceneView arSceneView;
    private GestureDetector gestureDetector;
    private boolean installAlreadyRequested;
    private boolean hasFinishedLoading;
    private Snackbar loadingSnackbar;

    private ModelRenderable baseRenderable;
    private ModelRenderable forkRenderable;
    private ModelRenderable barrelBaseRenderable;
    private ModelRenderable barrelTopRenderable;
    private ViewRenderable menuRenderable;

    private Node baseNode;
    private Node forkNode;
    private Node barrelBaseNode;
    private Node barrelTopNode;

    private float angle = 0.0f;
    private float qty = 0.1f;
    private boolean isRotatingHorizontally = false;
    private boolean isRotatingVertically = false;
    private Anchor cannonAnchor;
    private Node menuNode;

    private Node lightNode = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        arSceneView = findViewById(R.id.ar_scene_view);

        ActivityCompat.requestPermissions(
                this, new String[] {Manifest.permission.CAMERA}, 0);

        Utils.CheckARCoreSupported(this);

        loadModels();

        arSceneView
            .getScene()
            .addOnUpdateListener(
                frameTime -> {
                    // Check if a camera frame exists
                    Frame frame = arSceneView.getArFrame();
                    if (frame == null) {
                        return;
                    }
                    // Check if ARCore is actually tracking
                    if (frame.getCamera().getTrackingState() != TrackingState.TRACKING) {
                        return;
                    }
                    // If there is at least one Plane actively tracked, we can hide the loading Snackbar
                    for (Plane plane : frame.getUpdatedTrackables(Plane.class)) {
                        if (plane.getTrackingState() == TrackingState.TRACKING) {
                            hideLoadingMessage();
                            break;
                        }
                    }
                    // If we're rotating our cannon, do that here
                    if (isRotatingHorizontally) {
                        rotateHorizontally();
                    }
                    if (isRotatingVertically) {
                        rotateVertically();
                    }
                });

        gestureDetector =
                new GestureDetector(
                        this,
                        new GestureDetector.SimpleOnGestureListener() {
                            @Override
                            public boolean onSingleTapUp(MotionEvent e) {
                                return true;
                            }

                            @Override
                            public boolean onDown(MotionEvent e) {
                                onSceneTapped(e);
                                return true;
                            }
                        });

        arSceneView
                .getScene()
                .setOnTouchListener(
                        (HitTestResult hitTestResult, MotionEvent event) -> {
                            gestureDetector.onTouchEvent(event);
                            // Return false so touch events are also transmitted to the scene
                            return false;
                        });

    }

    @Override
    protected void onResume() {
        super.onResume();
        // If we don't have an ArSceneView, there's no point in doing anything
        if (arSceneView == null) {
            return;
        }

        // Make sure the ARCore app is installed
        Utils.RequestARCoreInstall(this, installAlreadyRequested);
        installAlreadyRequested = true;

        if (arSceneView.getSession() == null) {
            Session session;
            try {
                session = new Session(this);
                // IMPORTANT!!!  ArSceneView requires the `LATEST_CAMERA_IMAGE` non-blocking update mode.
                Config config = new Config(session);
                config.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE);
                session.configure(config);

                arSceneView.setupSession(session);

                if (arSceneView.getSession() != null) {
                    // We've just started, we don't have any plane detected
                    showLoadingMessage();
                }

                // Set the plane renderer texture to our custom image
                setPlaneRendererTexture();
            } catch (UnavailableArcoreNotInstalledException | UnavailableApkTooOldException
                    | UnavailableSdkTooOldException | UnavailableDeviceNotCompatibleException e) {
                e.printStackTrace();
            }
        }

        try {
            arSceneView.resume();
        } catch (CameraNotAvailableException ex) {
            Toast.makeText(this, "Unable to get camera", Toast.LENGTH_SHORT).show();
            finish();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (arSceneView != null) {
            arSceneView.pause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (arSceneView != null) {
            arSceneView.destroy();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            // Standard Android full-screen functionality.
            getWindow()
                    .getDecorView()
                    .setSystemUiVisibility(
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void loadModels() {
        // Load our 3 models and the menu layout as a 3D object
        CompletableFuture<ModelRenderable> baseStage =
                ModelRenderable.builder().setSource(this, Uri.parse("cannon_base.sfb")).build();
        CompletableFuture<ModelRenderable> forkStage =
                ModelRenderable.builder().setSource(this, Uri.parse("cannon_fork.sfb")).build();
        CompletableFuture<ModelRenderable> barrelBaseStage =
                ModelRenderable.builder().setSource(this, Uri.parse("cannon_barrel_base.sfb")).build();
        CompletableFuture<ModelRenderable> barrelTopStage =
                ModelRenderable.builder().setSource(this, Uri.parse("cannon_barrel_top.sfb")).build();
        CompletableFuture<ViewRenderable> menuStage =
                ViewRenderable.builder().setView(this, R.layout.view_menu).build();

        CompletableFuture.allOf(
                baseStage,
                forkStage,
                barrelBaseStage,
                barrelTopStage,
                menuStage
        ).handle((notUsed, throwable) -> {
            try {
                baseRenderable = baseStage.get();
                forkRenderable = forkStage.get();
                barrelBaseRenderable = barrelBaseStage.get();
                barrelTopRenderable = barrelTopStage.get();

                menuRenderable = menuStage.get();
                menuRenderable.getView().findViewById(R.id.item_rotate_hor).setOnClickListener(this::onMenuItemClicked);
                menuRenderable.getView().findViewById(R.id.item_rotate_vert).setOnClickListener(this::onMenuItemClicked);
                menuRenderable.getView().findViewById(R.id.item_fire).setOnClickListener(this::onMenuItemClicked);
                menuRenderable.getView().findViewById(R.id.item_light).setOnClickListener(this::onMenuItemClicked);

                hasFinishedLoading = true;
            } catch (InterruptedException | ExecutionException e) {
                Snackbar.make(findViewById(android.R.id.content), "Couldn't load renderables", Snackbar.LENGTH_LONG).show();
            }

            return null;
        });
    }

    private void showLoadingMessage() {
        if (loadingSnackbar == null || !loadingSnackbar.isShownOrQueued()) {
            loadingSnackbar =
                    Snackbar.make(
                            this.findViewById(android.R.id.content),
                            R.string.plane_finding,
                            Snackbar.LENGTH_INDEFINITE);
            loadingSnackbar.show();
        }
    }

    private void hideLoadingMessage() {
        if (loadingSnackbar != null) {
            loadingSnackbar.dismiss();
            loadingSnackbar = null;
        }
    }

    private void onSceneTapped(MotionEvent tap) {
        if (!hasFinishedLoading) {
            // We can't do anything yet.
            return;
        }

        // Check if the user's tap has intersected any detected planes
        Frame frame = arSceneView.getArFrame();
        if (frame != null && tap != null
                && frame.getCamera().getTrackingState() == TrackingState.TRACKING) {
            for (HitResult hit : frame.hitTest(tap)) {
                Trackable trackable = hit.getTrackable();
                if (trackable instanceof Plane
                        && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())) {
                    // We have a plane hit, display our object at the location
                    renderCannon(hit.createAnchor());
                }
            }
        }
    }

    private void displayMenu() {
        // Create a Node for the menu if we haven't created it before (if we've displayed it before)
        if (menuNode != null) {
            baseNode.removeChild(menuNode);
        }
        // Add the menu node
        menuNode = new Node();
        menuNode.setRenderable(menuRenderable);
        // Move it a bit upwards so it's visible above the cannon
        menuNode.setLocalPosition(new Vector3(0.0f, 0.3f, 0.0f));

        baseNode.addChild(menuNode);
    }

    private void renderCannon(Anchor anchor) {
        if (cannonAnchor != null) {
            cannonAnchor.detach();
        }
        cannonAnchor = anchor;

        baseNode = new Node();
        baseNode.setRenderable(baseRenderable);

        forkNode = new Node();
        forkNode.setRenderable(forkRenderable);
        baseNode.addChild(forkNode);

        barrelBaseNode = new Node();
        barrelBaseNode.setRenderable(barrelBaseRenderable);
        forkNode.addChild(barrelBaseNode);

        barrelTopNode = new Node();
        barrelTopNode.setRenderable(barrelTopRenderable);
        barrelBaseNode.addChild(barrelTopNode);

        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arSceneView.getScene());
        anchorNode.addChild(baseNode);

        baseNode.setOnTapListener((hitTestResult, motionEvent) -> displayMenu());
        forkNode.setOnTapListener((hitTestResult, motionEvent) -> displayMenu());
    }

    private void rotateHorizontally() {
        forkNode.setLocalRotation(Quaternion.axisAngle(new Vector3(0.0f, 1.0f, 0.0f), angle));
        angle += qty;
        if (angle >= 30f) {
            qty = -0.2f;
        } else if (angle <= 0) {
            qty = 0.2f;
        }
    }

    private void rotateVertically() {
        forkNode.setLocalRotation(Quaternion.axisAngle(new Vector3(1.0f, 0.0f, 0.0f), -angle));
        angle += qty;
        if (angle >= 30f) {
            qty = -0.2f;
        } else if (angle <= 0) {
            qty = 0.2f;
        }
    }

    private void fire() {
        Vector3 startPos = barrelTopNode.getLocalPosition();
        Vector3 endPos = new Vector3(startPos.x, startPos.y, startPos.z - 0.1f);
        ObjectAnimator animator = ObjectAnimator.ofObject(barrelTopNode, "localPosition", new Vector3Evaluator(), endPos);
        animator.setInterpolator(new OvershootInterpolator());
        animator.setDuration(200);
        animator.addListener(new Animator.AnimatorListener() {
            @Override
            public void onAnimationStart(Animator animation) {}

            @Override
            public void onAnimationEnd(Animator animation) {
                recoilBack(startPos);
            }

            @Override
            public void onAnimationCancel(Animator animation) {}

            @Override
            public void onAnimationRepeat(Animator animation) {}
        });
        animator.start();
    }

    private void recoilBack(Vector3 endPos) {
        ObjectAnimator animator = ObjectAnimator.ofObject(barrelTopNode, "localPosition", new Vector3Evaluator(), endPos);
        animator.setInterpolator(new LinearInterpolator());
        animator.setDuration(1000);
        animator.start();
    }

    private void onMenuItemClicked(View view) {
        // A menu item is clicked, check if the item is not being rendered (maximum one)
        // Then render it and setup the tap listeners so Andy's movement animation works
        switch (view.getId()) {
            case R.id.item_rotate_hor:
                isRotatingHorizontally = !isRotatingHorizontally;
                break;
            case R.id.item_rotate_vert:
                isRotatingVertically = !isRotatingVertically;
                break;
            case R.id.item_light:
                lightUpNode(forkNode);
                break;
            case R.id.item_fire:
            default:
                fire();
                break;
        }
        // We don't have a menu displayed anymore
        baseNode.removeChild(menuNode);
        menuNode = null;
    }

    private void lightUpNode(Node node) {
        if (lightNode == null) {
            Light yellowSpotlight = Light.builder(Light.Type.POINT)
                    .setColor(new Color(android.graphics.Color.WHITE))
                    .setShadowCastingEnabled(true)
                    .build();
            lightNode = new Node();
            lightNode.setLight(yellowSpotlight);
            lightNode.setLocalPosition(new Vector3(0.0f, 0.5f, 0.0f));
            node.addChild(lightNode);
        } else {
            node.removeChild(lightNode);
            lightNode = null;
        }
    }

    private void setPlaneRendererTexture() {
        // Make our custom texture repeat, this way you can use smaller images
        Texture.Sampler sampler =
                Texture.Sampler.builder()
                        .setMinFilter(Texture.Sampler.MinFilter.LINEAR)
                        .setMagFilter(Texture.Sampler.MagFilter.LINEAR)
                        .setWrapMode(Texture.Sampler.WrapMode.REPEAT)
                        .build();

        // Set the Texture on the PlaneRenderer's Material
        Texture.builder()
                .setSource(this, R.drawable.custom_texture)
                .setSampler(sampler)
                .build()
                .thenAccept(texture -> {
                    arSceneView
                            .getPlaneRenderer()
                            .getMaterial()
                            .thenAccept(material -> {
                                material.setTexture(PlaneRenderer.MATERIAL_TEXTURE, texture);
                            });
                });
    }
}
