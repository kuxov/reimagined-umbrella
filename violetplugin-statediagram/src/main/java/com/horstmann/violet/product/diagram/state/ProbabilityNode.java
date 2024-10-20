/*
 Violet - A program for editing UML diagrams.

 Copyright (C) 2007 Cay S. Horstmann (http://horstmann.com)
 Alexandre de Pellegrin (http://alexdp.free.fr);

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software
 Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.horstmann.violet.product.diagram.state;

import com.horstmann.violet.product.diagram.abstracts.edge.IEdge;
import com.horstmann.violet.product.diagram.abstracts.node.RectangularNode;
import com.horstmann.violet.product.diagram.abstracts.property.MultiLineString;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A node in a state diagram.
 */
public class ProbabilityNode extends RectangularNode
{
    /**
     * Construct a state node with a default size
     */
    public ProbabilityNode()
    {
        name = new MultiLineString();
        probability = "0.5";
    }
    
    @Override
    public boolean addConnection(IEdge e) {
    	if (e.getEnd() == null) {
    		return false;
    	}
    	if (this.equals(e.getEnd())) {
    		return false;
    	}
    	return super.addConnection(e);
    }

    @Override
    public Rectangle2D getBounds()
    {
        Rectangle2D b = name.getBounds();
        Point2D currentLocation = getLocation();
        double x = currentLocation.getX();
        double y = currentLocation.getY();
        double w = Math.max(b.getWidth(), DEFAULT_WIDTH);
        double h = Math.max(b.getHeight(), DEFAULT_HEIGHT);
        Rectangle2D currentBounds = new Rectangle2D.Double(x, y, w, h);
        Rectangle2D snappedBounds = getGraph().getGridSticker().snap(currentBounds);
        return snappedBounds;
    }

    @Override
    public void draw(Graphics2D g2)
    {
        super.draw(g2);
        // Perform drawing
        Shape shape = getShape();
        g2.setColor(Color.YELLOW);
        g2.fill(shape);
        g2.setColor(Color.RED);
        g2.draw(shape);
        g2.setColor(getTextColor());
        name.draw(g2, getBounds());
    }

    @Override
    public Shape getShape()
    {
        return new RoundRectangle2D.Double(getBounds().getX(), getBounds().getY(), getBounds().getWidth(), getBounds().getHeight(),
                ARC_SIZE, ARC_SIZE);
    }

    /**
     * Sets the name property value.
     * 
     * @param newValue the new state name
     */
    public void setName(MultiLineString newValue)
    {
        name = newValue;
    }

    /**
     * Gets the name property value.
     * 
     * @param the state name
     */
    public MultiLineString getName()
    {
        return name;
    }

    public ProbabilityNode clone()
    {
        ProbabilityNode cloned = (ProbabilityNode) super.clone();
        cloned.name = name.clone();
        return cloned;
    }

    private MultiLineString name;

    @Override
    public String getProbability() {
        return probability;
    }
    @Override
    public void setProbability(String probability) {
        this.probability = probability;
    }

    private String probability;

    private static int ARC_SIZE = 10;
    private static int DEFAULT_WIDTH = 20;
    private static int DEFAULT_HEIGHT = 20;
}
