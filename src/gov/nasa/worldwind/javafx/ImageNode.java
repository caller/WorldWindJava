/*
 * Copyright (C) 2012 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration.
 * All Rights Reserved.
 */
package gov.nasa.worldwind.javafx;

import com.sun.javafx.beans.event.AbstractNotifyListener;
import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.jmx.*;
import com.sun.javafx.scene.DirtyBits;
import com.sun.javafx.sg.prism.*;
import com.sun.javafx.tk.Toolkit;
import javafx.beans.Observable;
import javafx.css.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.image.*;
import java.util.*;

abstract class ImageNode extends Node
{
    ImageNode() {
        setAccessibleRole(AccessibleRole.IMAGE_VIEW);
        setNodeOrientation(NodeOrientation.LEFT_TO_RIGHT);
    }

    private boolean needsListeners = false;
    private Image image;

    protected final void setImage(Image _image) {
        boolean dimensionChanged = _image == null || this.image == null ||
            (this.image.getWidth() != _image.getWidth() ||
                this.image.getHeight() != _image.getHeight());

        if (needsListeners) {
            Toolkit
                .getImageAccessor()
                .getImageProperty(this.image)
                .removeListener(platformImageChangeListener.getWeakListener());
        }

        needsListeners = _image != null;
        this.image = _image;

        if (needsListeners) {
            Toolkit
                .getImageAccessor()
                .getImageProperty(_image)
                .addListener(platformImageChangeListener.getWeakListener());
        }

        if (dimensionChanged) {
            impl_geomChanged();
        }

        impl_markDirty(DirtyBits.NODE_CONTENTS);
    }

    private final AbstractNotifyListener platformImageChangeListener =
        new AbstractNotifyListener() {
            @Override
            public void invalidated(Observable valueModel) {
                impl_markDirty(DirtyBits.NODE_CONTENTS);
                impl_geomChanged();
            }
        };

    /**
     * @treatAsPrivate implementation detail
     * @deprecated This is an internal API that is not intended for use and will be removed in the next version
     */
    @Deprecated
    @Override
    public NGNode impl_createPeer() {
        return new NGImageView();
    }

    /**
     * @treatAsPrivate implementation detail
     * @deprecated This is an internal API that is not intended for use and will be removed in the next version
     */
    @Deprecated
    @Override
    public BaseBounds impl_computeGeomBounds(BaseBounds bounds, BaseTransform tx) {
        float width = 0;
        float height = 0;
        if (image != null) {
            width = (float)image.getWidth();
            height = (float)image.getHeight();
        }

        bounds = bounds.deriveWithNewBounds(0, 0, 0.0f, width, height, 0.0f);
        bounds = tx.transform(bounds, bounds);
        return bounds;
    }

    /**
     * @treatAsPrivate implementation detail
     * @deprecated This is an internal API that is not intended for use and will be removed in the next version
     */
    @Deprecated
    @Override
    protected boolean impl_computeContains(double localX, double localY) {
        if (localX < 0 || localY < 0 || image == null || localX > image.getWidth() || localY > image.getHeight()) {
            return false;
        }

        return Toolkit.getToolkit().imageContains(image.impl_getPlatformImage(), (float)localX, (float)localY);
    }

    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return getClassCssMetaData();
    }

    private void updateViewport() {
        if (image == null || image.impl_getPlatformImage() == null) {
            return;
        }

        final NGImageView peer = impl_getPeer();
        peer.setViewport(0, 0, 0, 0, (float)image.getWidth(), (float)image.getHeight());
    }

    /**
     * @treatAsPrivate implementation detail
     * @deprecated This is an internal API that is not intended for use and will be removed in the next version
     */
    @Deprecated
    @Override
    public void impl_updatePeer() {
        super.impl_updatePeer();

        final NGImageView peer = impl_getPeer();

        if (impl_isDirty(DirtyBits.NODE_CONTENTS)) {
            peer.setImage(image != null ? image.impl_getPlatformImage() : null);
        }

        // The NG part expects this to be called when image changes
        if (impl_isDirty(DirtyBits.NODE_VIEWPORT) || impl_isDirty(DirtyBits.NODE_CONTENTS)) {
            updateViewport();
        }
    }

    /**
     * @treatAsPrivate implementation detail
     * @deprecated This is an internal API that is not intended for use and will be removed in the next version
     */
    @Deprecated
    @Override
    public Object impl_processMXNode(MXNodeAlgorithm alg, MXNodeAlgorithmContext ctx) {
        return alg.processLeafNode(this, ctx);
    }

}
