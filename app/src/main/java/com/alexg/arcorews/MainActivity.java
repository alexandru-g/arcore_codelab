package com.alexg.arcorews;

import android.Manifest;
import android.animation.ObjectAnimator;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Toast;

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
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.HitTestResult;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.math.Vector3Evaluator;
import com.google.ar.sceneform.rendering.Color;
import com.google.ar.sceneform.rendering.Light;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.PlaneRenderer;
import com.google.ar.sceneform.rendering.Renderable;
import com.google.ar.sceneform.rendering.Texture;
import com.google.ar.sceneform.rendering.ViewRenderable;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

public class MainActivity extends AppCompatActivity {

    private ArSceneView arSceneView;
    private GestureDetector gestureDetector;
    private boolean installAlreadyRequested;
    private boolean hasFinishedLoading;
    private Snackbar loadingSnackbar;

    private ModelRenderable andyRenderable;
    private ModelRenderable distilleryRenderable;
    private ModelRenderable hayRenderable;
    private ViewRenderable menuRenderable;

    private Anchor menuAnchor;
    private AnchorNode menuAnchorNode;

    private Map<Renderable, AnchorNode> displayedObjects = new HashMap<>();
    private Node animatedNode;

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
                    // We've not displayed the loading Snackbar, no point in doing anything
                    if (loadingSnackbar == null) {
                        return;
                    }
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
                            return;
                        }
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
            Session session = null;
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
            } catch (UnavailableArcoreNotInstalledException e) {
                e.printStackTrace();
            } catch (UnavailableApkTooOldException e) {
                e.printStackTrace();
            } catch (UnavailableSdkTooOldException e) {
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
        CompletableFuture<ModelRenderable> andyStage =
                ModelRenderable.builder().setSource(this, Uri.parse("andy.sfb")).build();
        CompletableFuture<ModelRenderable> distilleryStage =
                ModelRenderable.builder().setSource(this, Uri.parse("distillery.sfb")).build();
        CompletableFuture<ModelRenderable> hayStage =
                ModelRenderable.builder().setSource(this, Uri.parse("hay.sfb")).build();
        CompletableFuture<ViewRenderable> menuStage =
                ViewRenderable.builder().setView(this, R.layout.view_menu).build();

        CompletableFuture.allOf(
                andyStage,
                distilleryStage,
                hayStage,
                menuStage
        ).handle((notUsed, throwable) -> {
            try {
                andyRenderable = andyStage.get();
                distilleryRenderable = distilleryStage.get();
                hayRenderable = hayStage.get();

                menuRenderable = menuStage.get();
                menuRenderable.getView().findViewById(R.id.item_andy).setOnClickListener(this::onMenuItemClicked);
                menuRenderable.getView().findViewById(R.id.item_distillery).setOnClickListener(this::onMenuItemClicked);
                menuRenderable.getView().findViewById(R.id.item_hay).setOnClickListener(this::onMenuItemClicked);

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
                    // We have a plane hit, display our menu at the location
                    displayMenu(hit.createAnchor());
                }
            }
        }
    }

    private void displayMenu(Anchor anchor) {
        // Make sure we don't have more than one Menu displayed at a time
        if (menuAnchor != null) {
            menuAnchor.detach();
        }
        // Mark that we've displayed the menu
        menuAnchor = anchor;

        // Create an AnchorNode for the menu if we haven't created it before (if we've displayed it before)
        if (menuAnchorNode == null) {
            // Actually render the menu
            Node node = new Node();
            node.setRenderable(menuRenderable);

            // And save the AnchorNode so we can hide the menu when rendering one of the models in its place
            menuAnchorNode = new AnchorNode();
            menuAnchorNode.setParent(arSceneView.getScene());
            menuAnchorNode.addChild(node);

        }
        menuAnchorNode.setAnchor(anchor);
        menuAnchorNode.setEnabled(true);
    }

    private Node renderNodeForAnchor(Renderable renderable, Anchor anchor) {
        // Create a Node, AnchorNode and attach them to the Scene to begin rendering the object
        Node node = new Node();
        node.setRenderable(renderable);

        AnchorNode anchorNode = new AnchorNode(anchor);
        anchorNode.setParent(arSceneView.getScene());
        anchorNode.addChild(node);

        displayedObjects.put(renderable, anchorNode);

        return node;
    }

    private void onMenuItemClicked(View view) {
        // A menu item is clicked, check if the item is not being rendered (maximum one)
        // Then render it and setup the tap listeners so Andy's movement animation works
        if (menuAnchor != null) {
            switch (view.getId()) {
                case R.id.item_distillery:
                    if (displayedObjects.containsKey(distilleryRenderable)) {
                        displayedObjects.get(distilleryRenderable).getAnchor().detach();
                    }
                    Node distilleryNode = renderNodeForAnchor(distilleryRenderable, menuAnchor);
                    distilleryNode.setOnTapListener((hitTestResult, motionEvent) -> {
                        animateNodeMovement(animatedNode, distilleryNode);
                    });
                    break;
                case R.id.item_hay:
                    if (displayedObjects.containsKey(hayRenderable)) {
                        displayedObjects.get(hayRenderable).getAnchor().detach();
                    }
                    Node hayNode = renderNodeForAnchor(hayRenderable, menuAnchor);
                    hayNode.setOnTapListener((hitTestResult, motionEvent) -> {
                        animateNodeMovement(animatedNode, hayNode);
                    });
                    break;
                default:
                    if (displayedObjects.containsKey(andyRenderable)) {
                        displayedObjects.get(andyRenderable).getAnchor().detach();
                    }
                    Node andyNode = renderNodeForAnchor(andyRenderable, menuAnchor);
                    andyNode.setOnTapListener((hitTestResult, motionEvent) -> {
                        animatedNode = andyNode;
                    });
                    // Set up a light over Andy so he's the real star
                    lightUpAndy(andyNode);
                    break;
            }
            // We don't have a menu displayed anymore
            menuAnchor = null;
            menuAnchorNode.setEnabled(false);
        }
    }

    private void animateNodeMovement(Node start, Node destination) {
        // Setup a standard ObjectAnimator that moves Andy to the destination Node by interpolating the two positions
        ObjectAnimator movementAnimator = new ObjectAnimator();
        Vector3 startPosition = start.getLocalPosition();
        Vector3 endPosition = Vector3.subtract(destination.getWorldPosition(), start.getWorldPosition());
        movementAnimator.setObjectValues(startPosition, endPosition);
        movementAnimator.setPropertyName("localPosition");
        movementAnimator.setEvaluator(new Vector3Evaluator());
        movementAnimator.setInterpolator(new LinearInterpolator());
        movementAnimator.setDuration(2000);
        movementAnimator.setTarget(start);
        movementAnimator.start();

        // After the animation finishes, check if Andy has collided with the destination Node (should)
        // and make it disappear
        new Handler().postDelayed(() -> {
            Node collidedNode = arSceneView.getScene().overlapTest(start);
            if (collidedNode != null) {
                if (displayedObjects.containsKey(collidedNode.getRenderable())) {
                    displayedObjects.get(collidedNode.getRenderable()).getAnchor().detach();
                }
            }
        }, 2000);
    }

    private void lightUpAndy(Node andyNode) {
        Light yellowSpotlight = Light.builder(Light.Type.SPOTLIGHT)
                .setColor(new Color(android.graphics.Color.YELLOW))
                .setShadowCastingEnabled(true)
                .build();
        andyNode.setLight(yellowSpotlight);
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
