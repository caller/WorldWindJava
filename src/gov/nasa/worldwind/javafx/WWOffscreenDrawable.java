/*
 * Copyright (C) 2018 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration.
 * All Rights Reserved.
 */
package gov.nasa.worldwind.javafx;

import com.jogamp.opengl.util.texture.TextureIO;
import gov.nasa.worldwind.*;
import gov.nasa.worldwind.cache.*;
import gov.nasa.worldwind.event.*;
import gov.nasa.worldwind.exception.WWAbsentRequirementException;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.pick.*;
import gov.nasa.worldwind.util.*;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.util.Duration;
import javax.media.opengl.*;
import java.beans.*;
import java.util.concurrent.*;
import java.util.logging.Level;

class WWOffscreenDrawable extends JfxWorldWindowImpl
{
    // Default time in milliseconds that the view must remain unchanged before
    // the {@link View#VIEW_STOPPED} message is sent.
    private static final long VIEW_STOP_TIME = 1000;

    private GLAutoDrawable drawable;
    private boolean shuttingDown = false;
    private long lastViewID;
    private ScheduledFuture viewRefreshTask;
    private boolean enableGpuCacheReinitialization = true;
    private boolean firstInit = true;
    private boolean sceneDirty = false;

    private final AnimationTimer redrawTimer = new AnimationTimer() {
        @Override
        public void handle(long now)
        {
            if (sceneDirty)
            {
                sceneDirty = false;
                drawable.display();
            }
        }
    };

    private final GLEventListener glEventListener = new GLEventListener()
    {
        /**
         * Called by the drawable immediately after the OpenGL context is initialized.
         * Can be used to perform one-time OpenGL initialization such as setup of lights
         * and display lists.
         *
         * Note that this method may be called more than once if the underlying OpenGL context
         * for the GLAutoDrawable is destroyed and recreated, for example if a GLCanvas is
         * removed from the widget hierarchy and later added again.
         */
        @Override
        public void init(GLAutoDrawable glAutoDrawable)
        {
            if (!isGLContextCompatible(glAutoDrawable.getContext()))
            {
                String msg = Logging.getMessage("WorldWindowGLAutoDrawable.IncompatibleGLContext",
                    glAutoDrawable.getContext());
                callRenderingExceptionListeners(new WWAbsentRequirementException(msg));
            }

            for (String funcName : getRequiredOglFunctions())
            {
                if (!glAutoDrawable.getGL().isFunctionAvailable(funcName))
                {
                    //noinspection ThrowableInstanceNeverThrown
                    callRenderingExceptionListeners(new WWAbsentRequirementException(funcName + " not available"));
                }
            }

            for (String extName : getRequiredOglExtensions())
            {
                if (!glAutoDrawable.getGL().isExtensionAvailable(extName))
                {
                    //noinspection ThrowableInstanceNeverThrown
                    callRenderingExceptionListeners(new WWAbsentRequirementException(extName + " not available"));
                }
            }

            if (firstInit)
            {
                firstInit = false;
            }
            else if (enableGpuCacheReinitialization)
            {
                reinitialize(glAutoDrawable);
            }

            // Disables use of the OpenGL extension GL_ARB_texture_rectangle by JOGL's Texture creation utility.
            //
            // Between version 1.1.1 and version 2.x, JOGL modified its texture creation utility to favor
            // GL_ARB_texture_rectangle over GL_ARB_texture_non_power_of_two on Mac OS X machines with ATI graphics cards. See
            // the following URL for details on the texture rectangle extension: http://www.opengl.org/registry/specs/ARB/texture_rectangle.txt
            //
            // There are two problems with favoring texture rectangle for non power of two textures:
            // 1) As of November 2012, we cannot find any evidence that the GL_ARB_texture_non_power_of_two extension is
            //    problematic on Mac OS X machines with ATI graphics cards. The texture rectangle extension is more limiting
            //    than the NPOT extension, and therefore not preferred.
            // 2) World Wind assumes that a texture's target is always GL_TEXTURE_2D, and therefore incorrectly displays
            //    textures with the target GL_TEXTURE_RECTANGLE.
            TextureIO.setTexRectEnabled(false);

            //drawable.setGL(new DebugGL(drawable.getGL())); // uncomment to use the debug drawable
        }

        /**
         * Notifies the listener to perform the release of all OpenGL resources per GLContext,
         * such as memory buffers and GLSL programs. Called by the drawable before the OpenGL
         * context is destroyed by an external event, like a reconfiguration of the GLAutoDrawable
         * closing an attached window, but also manually by calling destroy.
         *
         * Note that this event does not imply the end of life of the application. It could be
         * produced with a followup call to init(GLAutoDrawable) in case the GLContext has been
         * recreated, e.g. due to a pixel configuration change in a multihead environment.
         */
        @Override
        public void dispose(GLAutoDrawable glAutoDrawable)
        {
        }

        /**
         * Called by the drawable to initiate OpenGL rendering by the client.
         * After all GLEventListeners have been notified of a display event, the drawable
         * will swap its buffers if setAutoSwapBufferMode is enabled.
         */
        @Override
        public void display(GLAutoDrawable glAutoDrawable)
        {
            // Performing shutdown here in order to do so with a current GL context for GL resource disposal.
            if (shuttingDown)
            {
                try
                {
                    doShutdown();
                }
                catch (Exception e)
                {
                    Logging.logger().log(Level.SEVERE, Logging.getMessage(
                        "WorldWindowGLCanvas.ExceptionWhileShuttingDownWorldWindow"), e);
                }

                return;
            }

            try
            {
                SceneController sc = getSceneController();
                if (sc == null)
                {
                    String msg = Logging.getMessage("WorldWindowGLCanvas.ScnCntrllerNullOnRepaint");
                    Logging.logger().severe(msg);
                    throw new IllegalStateException(msg);
                }

                // Determine if the view has changed since the last frame.
                checkForViewChange();

                Position positionAtStart = getCurrentPosition();
                PickedObject selectionAtStart = getCurrentSelection();
                PickedObjectList boxSelectionAtStart = getCurrentBoxSelection();

                try
                {
                    callRenderingListeners(new RenderingEvent(drawable, RenderingEvent.BEFORE_RENDERING));
                }
                catch (Exception e)
                {
                    Logging.logger().log(Level.SEVERE,
                        Logging.getMessage("WorldWindowGLAutoDrawable.ExceptionDuringGLEventListenerDisplay"), e);
                }

                final int redrawDelay = sc.repaint();
                if (redrawDelay > 0)
                {
                    new JfxTimer(Duration.millis(redrawDelay), new Runnable() {
                        @Override
                        public void run()
                        {
                            redraw();
                        }
                    }).start();
                }

                Double frameTime = sc.getFrameTime();
                if (frameTime != null)
                {
                    setValue(PerformanceStatistic.FRAME_TIME, frameTime);
                }

                Double frameRate = sc.getFramesPerSecond();
                if (frameRate != null)
                {
                    setValue(PerformanceStatistic.FRAME_RATE, frameRate);
                }

                // Dispatch the rendering exceptions accumulated by the SceneController during this frame to our
                // RenderingExceptionListeners.
                Iterable<Throwable> renderingExceptions = sc.getRenderingExceptions();
                if (renderingExceptions != null)
                {
                    for (Throwable t : renderingExceptions)
                    {
                        if (t != null)
                        {
                            try
                            {
                                callRenderingExceptionListeners(t);
                            }
                            catch (Exception e)
                            {
                                Logging.logger().log(Level.SEVERE,
                                    Logging.getMessage("WorldWindowGLAutoDrawable.ExceptionDuringGLEventListenerDisplay"), e);
                            }
                        }
                    }
                }

                try
                {
                    callRenderingListeners(new RenderingEvent(drawable, RenderingEvent.AFTER_RENDERING));
                }
                catch (Exception e)
                {
                    Logging.logger().log(Level.SEVERE,
                        Logging.getMessage("WorldWindowGLAutoDrawable.ExceptionDuringGLEventListenerDisplay"), e);
                }

                // Position and selection notification occurs only on triggering conditions, not same-state conditions:
                // start == null, end == null: nothing selected -- don't notify
                // start == null, end != null: something now selected -- notify
                // start != null, end == null: something was selected but no longer is -- notify
                // start != null, end != null, start != end: something new was selected -- notify
                // start != null, end != null, start == end: same thing is selected -- don't notify

                Position positionAtEnd = getCurrentPosition();
                if (positionAtStart != null || positionAtEnd != null)
                {
                    // call the listener if both are not null or positions are the same
                    if (positionAtStart != null && positionAtEnd != null)
                    {
                        if (!positionAtStart.equals(positionAtEnd))
                        {
                            callPositionListeners(
                                new PositionEvent(drawable, sc.getPickPoint(), positionAtStart, positionAtEnd));
                        }
                    }
                    else
                    {
                        callPositionListeners(
                            new PositionEvent(drawable, sc.getPickPoint(), positionAtStart, positionAtEnd));
                    }
                }

                PickedObject selectionAtEnd = getCurrentSelection();
                if (selectionAtStart != null || selectionAtEnd != null)
                {
                    callSelectListeners(
                        new SelectEvent(drawable, SelectEvent.ROLLOVER, sc.getPickPoint(), sc.getPickedObjectList()));
                }

                PickedObjectList boxSelectionAtEnd = getCurrentBoxSelection();
                if (boxSelectionAtStart != null || boxSelectionAtEnd != null)
                {
                    callSelectListeners(
                        new SelectEvent(
                            drawable, SelectEvent.BOX_ROLLOVER, sc.getPickRectangle(), sc.getObjectsInPickRectangle()));
                }
            }
            catch (Exception e)
            {
                Logging.logger().log(Level.SEVERE, Logging.getMessage(
                    "WorldWindowGLCanvas.ExceptionAttemptingRepaintWorldWindow"), e);
            }
        }

        /**
         * Called by the drawable during the first repaint after the component has been resized.
         * The client can update the viewport and view volume of the window appropriately, for
         * example by a call to GL.glViewport(int, int, int, int); note that for convenience the
         * component has already called glViewport(x, y, width, height) when this method is called,
         * so the client may not have to do anything in this method.
         */
        @Override
        public void reshape(GLAutoDrawable glAutoDrawable, int i, int i1, int i2, int i3)
        {
        }
    };

    private final PropertyChangeListener sceneControllerListener = new PropertyChangeListener()
    {
        @Override
        public void propertyChange(PropertyChangeEvent propertyChangeEvent)
        {
            if (propertyChangeEvent == null)
            {
                String msg = Logging.getMessage("nullValue.PropertyChangeEventIsNull");
                Logging.logger().severe(msg);
                throw new IllegalArgumentException(msg);
            }

            redraw(); // Queue a JOGL display request.
        }
    };

    WWOffscreenDrawable() {
        SceneController sc = getSceneController();
        if (sc != null)
        {
            sc.addPropertyChangeListener(sceneControllerListener);
        }

        redrawTimer.start();
    }

    public void initDrawable(GLAutoDrawable glAutoDrawable)
    {
        if (glAutoDrawable == null)
        {
            String msg = Logging.getMessage("nullValue.DrawableIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.drawable = glAutoDrawable;
        this.drawable.setAutoSwapBufferMode(false);
        this.drawable.addGLEventListener(glEventListener);
    }

    @Override
    public GLContext getContext()
    {
        return drawable.getContext();
    }

    @Override
    public boolean isEnableGpuCacheReinitialization()
    {
        return enableGpuCacheReinitialization;
    }

    @Override
    public void setEnableGpuCacheReinitialization(boolean enableGpuCacheReinitialization)
    {
        this.enableGpuCacheReinitialization = enableGpuCacheReinitialization;
    }

    public void initGpuResourceCache(GpuResourceCache cache)
    {
        if (cache == null)
        {
            String msg = Logging.getMessage("nullValue.GpuResourceCacheIsNull");
            Logging.logger().severe(msg);
            throw new IllegalArgumentException(msg);
        }

        this.setGpuResourceCache(cache);
    }

    @Override
    public void redraw()
    {
        sceneDirty = true;
    }

    @Override
    public void redrawNow()
    {
        sceneDirty = true;
    }

    @Override
    public void shutdown()
    {
        shuttingDown = true;
        redrawNow();
    }

    private void doShutdown()
    {
        super.shutdown();
        redrawTimer.stop();
        drawable.removeGLEventListener(glEventListener);
        shuttingDown = false;
    }

    /**
     * Determine if the view has changed since the previous frame. If the view has changed, schedule a task that will
     * send a {@link View#VIEW_STOPPED} to the Model if the view does not change for {@link #VIEW_STOP_TIME}
     * milliseconds.
     */
    private void checkForViewChange()
    {
        long viewId = this.getView().getViewStateID();

        // Determine if the view has changed since the previous frame.
        if (viewId != this.lastViewID)
        {
            // View has changed, capture the new viewStateID
            this.lastViewID = viewId;

            // Cancel the previous view stop task and schedule a new one because the view has changed.
            this.scheduleViewStopTask(VIEW_STOP_TIME);
        }
    }

    private void scheduleViewStopTask(long delay)
    {
        Runnable viewStoppedTask = new Runnable()
        {
            public void run()
            {
                Platform.runLater(new Runnable()
                {
                    @Override
                    public void run()
                    {
                        onMessage(new Message(View.VIEW_STOPPED, WWOffscreenDrawable.this));
                    }
                });
            }
        };

        // Cancel the previous view stop task
        if (this.viewRefreshTask != null)
        {
            this.viewRefreshTask.cancel(false);
        }

        // Schedule the task for execution in delay milliseconds
        this.viewRefreshTask = WorldWind.getScheduledTaskService()
            .addScheduledTask(viewStoppedTask, delay, TimeUnit.MILLISECONDS);
    }

    @Override
    public void onMessage(Message msg)
    {
        Model model = this.getModel();
        if (model != null)
        {
            model.onMessage(msg);
        }
    }

    @SuppressWarnings({"UnusedParameters"})
    private void reinitialize(GLAutoDrawable glAutoDrawable)
    {
        // Clear the gpu resource cache if the window is reinitializing, most likely with a new gl hardware context.
        if (this.getGpuResourceCache() != null)
        {
            this.getGpuResourceCache().clear();
        }

        this.getSceneController().reinitialize();
    }

    private boolean isGLContextCompatible(GLContext context)
    {
        return context != null && context.isGL2();
    }

    private String[] getRequiredOglFunctions()
    {
        return new String[] {"glActiveTexture", "glClientActiveTexture"};
    }

    private String[] getRequiredOglExtensions()
    {
        return new String[] {};
    }

}
