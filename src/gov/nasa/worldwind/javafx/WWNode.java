/*
 * Copyright (C) 2018 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration.
 * All Rights Reserved.
 */
package gov.nasa.worldwind.javafx;

import com.sun.javafx.tk.Toolkit;
import gov.nasa.worldwind.*;
import gov.nasa.worldwind.avlist.*;
import gov.nasa.worldwind.cache.GpuResourceCache;
import gov.nasa.worldwind.event.*;
import gov.nasa.worldwind.geom.Position;
import gov.nasa.worldwind.pick.PickedObjectList;
import gov.nasa.worldwind.util.*;
import javafx.beans.property.*;
import javafx.event.*;
import javafx.event.EventHandler;
import javafx.scene.image.*;
import javafx.scene.input.*;

import javax.media.opengl.*;
import java.beans.*;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;

public class WWNode extends ImageNode implements WorldWindow
{
    private static final Field prismImagePixelBufferField;
    private static final Field prismImageSerialField;
    private static final Method pixelsDirtyMethod;

    static
    {
        Method method = null;
        Field pixelBufferField = null;
        Field serialField = null;

        try
        {
            method = Image.class.getDeclaredMethod("pixelsDirty");
            method.setAccessible(true);

            pixelBufferField = com.sun.prism.Image.class.getDeclaredField("pixelBuffer");
            pixelBufferField.setAccessible(true);

            serialField = com.sun.prism.Image.class.getDeclaredField("serial");
            serialField.setAccessible(true);
        }
        catch (NoSuchMethodException e)
        {
            e.printStackTrace();
        }
        catch (NoSuchFieldException e)
        {
            e.printStackTrace();
        }

        pixelsDirtyMethod = method;
        prismImagePixelBufferField = pixelBufferField;
        prismImageSerialField = serialField;
    }

    private WWOffscreenDrawable wwd;
    private GLOffscreenAutoDrawable offscreenDrawable;
    private WritableImage writableImage;
    private ByteBuffer pixelBuffer;

    private final DoubleProperty width = new SimpleDoubleProperty() {
        @Override
        protected void invalidated()
        {
            super.invalidated();
            sizeInvalidated();
        }
    };

    private final DoubleProperty height = new SimpleDoubleProperty() {
        @Override
        protected void invalidated()
        {
            super.invalidated();
            sizeInvalidated();
        }
    };

    private final RenderingListener renderingListener = new RenderingListener()
    {
        @Override
        public void stageChanged(RenderingEvent event)
        {
            // identity comparison
            if (pixelBuffer != null && event.getStage() == RenderingEvent.AFTER_RENDERING) {
                int width = (int)getWidth();
                int height = (int)getHeight();

                GL gl = wwd.getContext().getGL();
                gl.glReadPixels(0, 0, width, height, GL.GL_BGRA, GL.GL_UNSIGNED_BYTE, pixelBuffer);

                // Since we write directly to the WritableImage's pixel buffer, we need to manually set
                // the internal dirty flag and increase the serial number of the image.
                try
                {
                    pixelsDirtyMethod.invoke(writableImage);
                    int[] serial = (int[])prismImageSerialField.get(writableImage.impl_getPlatformImage());
                    serial[0]++;
                }
                catch (Exception e)
                {
                    // will not happen
                }

                // We could also use the PixelWriter API to write to the WritableImage, but this has the
                // disadvantage of one additional internal buffer copy.
                //
                //PixelWriter pixelWriter = writableImage.getPixelWriter();
                //pixelWriter.setPixels(
                //    0, 0, width, height, PixelFormat.getByteBgraPreInstance(), pixelBuffer, width * 4);
            }
        }
    };

    private final EventHandler<Event> requestFocusHandler = new EventHandler<Event>()
    {
        @Override
        public void handle(Event event)
        {
            if (!isFocused()) {
                requestFocus();
            }
        }
    };

    public WWNode()
    {
        GLProfile profile = Configuration.getMaxCompatibleGLProfile();
        GLCapabilities caps = Configuration.getRequiredGLCapabilities();
        caps.setOnscreen(false);
        caps.setDoubleBuffered(false);
        caps.setHardwareAccelerated(true);

        offscreenDrawable = GLDrawableFactory.getFactory(profile).createOffscreenAutoDrawable(
            null, caps, new BasicGLCapabilitiesChooser(), 640, 480);

        wwd = ((WWOffscreenDrawable)WorldWind.createConfigurationComponent(AVKey.WORLD_WINDOW_CLASS_NAME));
        wwd.addRenderingListener(renderingListener);
        wwd.initDrawable(offscreenDrawable);
        wwd.initGpuResourceCache(JfxWorldWindowImpl.createGpuResourceCache());
        createView();
        createDefaultInputHandler();
        WorldWind.addPropertyChangeListener(WorldWind.SHUTDOWN_EVENT, wwd);

        setScaleY(-1);
        addEventHandler(MouseEvent.MOUSE_PRESSED, requestFocusHandler);
        addEventHandler(TouchEvent.TOUCH_PRESSED, requestFocusHandler);
    }

    public double getWidth()
    {
        return width.get();
    }

    public void setWidth(double width)
    {
        this.width.set(width);
    }

    public double getHeight()
    {
        return height.get();
    }

    public void setHeight(double height)
    {
        this.height.set(height);
    }

    public DoubleProperty widthProperty()
    {
        return width;
    }

    public DoubleProperty heightProperty()
    {
        return height;
    }

    /** Constructs and attaches the {@link View} for this <code>WorldWindow</code>. */
    protected void createView()
    {
        this.setView((View)WorldWind.createConfigurationComponent(AVKey.VIEW_CLASS_NAME));
    }

    /** Constructs and attaches the {@link InputHandler} for this <code>WorldWindow</code>. */
    protected void createDefaultInputHandler()
    {
        this.setInputHandler((InputHandler)WorldWind.createConfigurationComponent(AVKey.INPUT_HANDLER_CLASS_NAME));
    }

    private void sizeInvalidated() {
        int width = (int)this.width.get();
        int height = (int)this.height.get();

        if (width > 0 && height > 0) {
            writableImage = new WritableImage(width, height);
            pixelBuffer = getPixelBuffer(writableImage);
            offscreenDrawable.setSize(width, height);
            setImage(writableImage);
        } else {
            writableImage = null;
            pixelBuffer = null;
            setImage(null);
        }
    }

    private static ByteBuffer getPixelBuffer(WritableImage image) {
        try
        {
            return (ByteBuffer)prismImagePixelBufferField.get(
                Toolkit.getImageAccessor().getImageProperty(image).get());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void setModel(Model model)
    {
        wwd.setModel(model);
    }

    @Override
    public Model getModel()
    {
        return wwd.getModel();
    }

    @Override
    public void setView(View view)
    {
        wwd.setView(view);
    }

    @Override
    public View getView()
    {
        return wwd.getView();
    }

    @Override
    public void setModelAndView(Model model, View view)
    {
        wwd.setModel(model);
        wwd.setView(view);
    }

    @Override
    public SceneController getSceneController()
    {
        return wwd.getSceneController();
    }

    @Override
    public void setSceneController(SceneController sceneController)
    {
        wwd.setSceneController(sceneController);
    }

    @Override
    public InputHandler getInputHandler()
    {
        return wwd.getInputHandler();
    }

    @Override
    public void setInputHandler(InputHandler inputHandler)
    {
        if (wwd.getInputHandler() != null)
        {
            wwd.getInputHandler().setEventSource(null);
        }

        if (inputHandler != null)
        {
            wwd.setInputHandler(inputHandler);
            inputHandler.setEventSource(this);
        }
        else
        {
            wwd.setInputHandler(new NoOpInputHandler());
        }
    }

    @Override
    public void addRenderingListener(RenderingListener listener)
    {
        wwd.addRenderingListener(listener);
    }

    @Override
    public void removeRenderingListener(RenderingListener listener)
    {
        wwd.removeRenderingListener(listener);
    }

    @Override
    public void addSelectListener(SelectListener listener)
    {
        wwd.getInputHandler().addSelectListener(listener);
        wwd.addSelectListener(listener);
    }

    @Override
    public void removeSelectListener(SelectListener listener)
    {
        wwd.getInputHandler().removeSelectListener(listener);
        wwd.removeSelectListener(listener);
    }

    @Override
    public void addPositionListener(PositionListener listener)
    {
        wwd.addPositionListener(listener);
    }

    @Override
    public void removePositionListener(PositionListener listener)
    {
        wwd.removePositionListener(listener);
    }

    @Override
    public void redraw()
    {
        wwd.redraw();
    }

    @Override
    public void redrawNow()
    {
        wwd.redrawNow();
    }

    @Override
    public Position getCurrentPosition()
    {
        return wwd.getCurrentPosition();
    }

    @Override
    public PickedObjectList getObjectsAtCurrentPosition()
    {
        SceneController sceneController = wwd.getSceneController();
        return sceneController != null ? sceneController.getPickedObjectList() : null;
    }

    @Override
    public PickedObjectList getObjectsInSelectionBox()
    {
        SceneController sceneController = wwd.getSceneController();
        return sceneController != null ? sceneController.getObjectsInPickRectangle() : null;
    }

    @Override
    public GpuResourceCache getGpuResourceCache()
    {
        return wwd.getGpuResourceCache();
    }

    @Override
    public void setPerFrameStatisticsKeys(Set<String> keys)
    {
        wwd.setPerFrameStatisticsKeys(keys);
    }

    @Override
    public Collection<PerformanceStatistic> getPerFrameStatistics()
    {
        return wwd.getPerFrameStatistics();
    }

    @Override
    public void shutdown()
    {
        wwd.shutdown();
    }

    @Override
    public void addRenderingExceptionListener(RenderingExceptionListener listener)
    {
        wwd.addRenderingExceptionListener(listener);
    }

    @Override
    public void removeRenderingExceptionListener(RenderingExceptionListener listener)
    {
        wwd.removeRenderingExceptionListener(listener);
    }

    @Override
    public GLContext getContext()
    {
        return wwd.getContext();
    }

    @Override
    public boolean isEnableGpuCacheReinitialization()
    {
        return wwd.isEnableGpuCacheReinitialization();
    }

    @Override
    public void setEnableGpuCacheReinitialization(boolean enableGpuCacheReinitialization)
    {
        wwd.setEnableGpuCacheReinitialization(enableGpuCacheReinitialization);
    }

    @Override
    public Object setValue(String key, Object value)
    {
        return wwd.setValue(key, value);
    }

    @Override
    public AVList setValues(AVList avList)
    {
        return wwd.setValues(avList);
    }

    @Override
    public Object getValue(String key)
    {
        return wwd.getValue(key);
    }

    @Override
    public Collection<Object> getValues()
    {
        return wwd.getValues();
    }

    @Override
    public String getStringValue(String key)
    {
        return wwd.getStringValue(key);
    }

    @Override
    public Set<Map.Entry<String, Object>> getEntries()
    {
        return wwd.getEntries();
    }

    @Override
    public boolean hasKey(String key)
    {
        return wwd.hasKey(key);
    }

    @Override
    public Object removeKey(String key)
    {
        return wwd.removeKey(key);
    }

    @Override
    public void addPropertyChangeListener(String propertyName, PropertyChangeListener listener)
    {
        wwd.addPropertyChangeListener(propertyName, listener);
    }

    @Override
    public void removePropertyChangeListener(String propertyName, PropertyChangeListener listener)
    {
        wwd.removePropertyChangeListener(propertyName, listener);
    }

    @Override
    public void addPropertyChangeListener(PropertyChangeListener listener)
    {
        wwd.addPropertyChangeListener(listener);
    }

    @Override
    public void removePropertyChangeListener(PropertyChangeListener listener)
    {
        wwd.removePropertyChangeListener(listener);
    }

    @Override
    public void firePropertyChange(String propertyName, Object oldValue, Object newValue)
    {
        wwd.firePropertyChange(propertyName, oldValue, newValue);
    }

    @Override
    public void firePropertyChange(PropertyChangeEvent propertyChangeEvent)
    {
        wwd.firePropertyChange(propertyChangeEvent);
    }

    @Override
    public AVList copy()
    {
        return wwd.copy();
    }

    @Override
    public AVList clearList()
    {
        return wwd.clearList();
    }

}
