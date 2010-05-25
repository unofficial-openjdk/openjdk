/*
 * Copyright (c) 2000, 2006, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
/*
 * @author    IBM Corp.
 *
 * Copyright IBM Corp. 1999-2000.  All rights reserved.
 */

package javax.management.modelmbean;

import static com.sun.jmx.defaults.JmxProperties.MODELMBEAN_LOGGER;
import com.sun.jmx.mbeanserver.GetPropertyAction;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamField;
import java.security.AccessController;
import java.util.logging.Level;

import javax.management.Descriptor;
import javax.management.DescriptorAccess;
import javax.management.MBeanNotificationInfo;
import javax.management.RuntimeOperationsException;

/**
 * The ModelMBeanNotificationInfo object describes a notification emitted
 * by a ModelMBean.
 * It is a subclass of MBeanNotificationInfo with the addition of an
 * associated Descriptor and an implementation of the Descriptor interface.
 * <P>
 * The fields in the descriptor are defined, but not limited to,
 * the following:
 * <PRE>
 * name           : notification name
 * descriptorType : must be "notification"
 * severity       : 0-6 where 0: unknown; 1: non-recoverable;
 *                  2: critical, failure; 3: major, severe;
 *                  4: minor, marginal, error; 5: warning;
 *                  6: normal, cleared, informative
 * messageID      : unique key for message text (to allow translation,
 *                  analysis)
 * messageText    : text of notification
 * log            : T - log message F - do not log message
 * logfile        : string fully qualified file name appropriate for
 *                  operating system
 * visibility     : 1-4 where 1: always visible 4: rarely visible
 * presentationString : xml formatted string to allow presentation of data
 * </PRE>
 * The default descriptor contains the name, descriptorType, displayName
 * and severity(=6) fields.
 *
 * <p>The <b>serialVersionUID</b> of this class is <code>-7445681389570207141L</code>.
 *
 * @since 1.5
 */

// Sun Microsystems, Sept. 2002: Revisited for JMX 1.2 (DF)
//
@SuppressWarnings("serial")  // serialVersionUID is not constant
public class ModelMBeanNotificationInfo
    extends MBeanNotificationInfo
    implements DescriptorAccess {

    // Serialization compatibility stuff:
    // Two serial forms are supported in this class. The selected form
    // depends on system property "jmx.serial.form":
    //  - "1.0" for JMX 1.0
    //  - any other value for JMX 1.1 and higher
    //
    // Serial version for old serial form
    private static final long oldSerialVersionUID = -5211564525059047097L;
    //
    // Serial version for new serial form
    private static final long newSerialVersionUID = -7445681389570207141L;
    //
    // Serializable fields in old serial form
    private static final ObjectStreamField[] oldSerialPersistentFields =
    {
      new ObjectStreamField("notificationDescriptor", Descriptor.class),
      new ObjectStreamField("currClass", String.class)
    };
    //
    // Serializable fields in new serial form
    private static final ObjectStreamField[] newSerialPersistentFields =
    {
      new ObjectStreamField("notificationDescriptor", Descriptor.class)
    };
    //
    // Actual serial version and serial form
    private static final long serialVersionUID;
    /**
     * @serialField notificationDescriptor Descriptor The descriptor
     *   containing the appropriate metadata for this instance
     */
    private static final ObjectStreamField[] serialPersistentFields;
    private static boolean compat = false;
    static {
        try {
            GetPropertyAction act = new GetPropertyAction("jmx.serial.form");
            String form = AccessController.doPrivileged(act);
            compat = (form != null && form.equals("1.0"));
        } catch (Exception e) {
            // OK: No compat with 1.0
        }
        if (compat) {
            serialPersistentFields = oldSerialPersistentFields;
            serialVersionUID = oldSerialVersionUID;
        } else {
            serialPersistentFields = newSerialPersistentFields;
            serialVersionUID = newSerialVersionUID;
        }
    }
    //
    // END Serialization compatibility stuff

    /**
     * @serial The descriptor containing the appropriate metadata for
     *         this instance
     */
    private Descriptor notificationDescriptor;

    private static final String currClass = "ModelMBeanNotificationInfo";

    /**
     * Constructs a ModelMBeanNotificationInfo object with a default
     * descriptor.
     *
     * @param notifTypes The array of strings (in dot notation) containing
     *     the notification types that may be emitted.
     * @param name The name of the Notification class.
     * @param description A human readable description of the
     *     Notification. Optional.
     **/
    public ModelMBeanNotificationInfo(String[] notifTypes,
                                      String name,
                                      String description) {
        this(notifTypes,name,description,null);
    }

    /**
     * Constructs a ModelMBeanNotificationInfo object.
     *
     * @param notifTypes The array of strings (in dot notation)
     *        containing the notification types that may be emitted.
     * @param name The name of the Notification class.
     * @param description A human readable description of the Notification.
     *        Optional.
     * @param descriptor An instance of Descriptor containing the
     *        appropriate metadata for this instance of the
     *        MBeanNotificationInfo. If it is null a default descriptor
     *        will be created. If the descriptor does not contain the
     *        fields "displayName" or "severity" these fields are added
     *        in the  descriptor with their default values.
     *
     * @exception RuntimeOperationsException Wraps an
     *    {@link IllegalArgumentException}. The descriptor is invalid, or
     *    descriptor field "name" is not equal to parameter name, or
     *    descriptor field "DescriptorType" is not equal to "notification".
     *
     **/
    public ModelMBeanNotificationInfo(String[] notifTypes,
                                      String name,
                                      String description,
                                      Descriptor descriptor) {
        super(notifTypes, name, description);
        final String mth = "ModelMBeanNotificationInfo";
        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    mth, "Entry");
        }
        applyDescriptor(descriptor, mth);
    }

    /**
     * Constructs a new ModelMBeanNotificationInfo object from this
     * ModelMBeanNotfication Object.
     *
     * @param inInfo the ModelMBeanNotificationInfo to be duplicated
     *
     **/
    public ModelMBeanNotificationInfo(ModelMBeanNotificationInfo inInfo) {
        this(inInfo.getNotifTypes(),
             inInfo.getName(),
             inInfo.getDescription(),inInfo.getDescriptor());
    }

    /**
     * Creates and returns a new ModelMBeanNotificationInfo which is a
     * duplicate of this ModelMBeanNotificationInfo.
     **/
    public Object clone () {
        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    "clone()", "Entry");
        }
        return(new ModelMBeanNotificationInfo(this));
    }

    /**
     * Returns a copy of the associated Descriptor for the
     * ModelMBeanNotificationInfo.
     *
     * @return Descriptor associated with the
     * ModelMBeanNotificationInfo object.
     *
     * @see #setDescriptor
     **/
    public Descriptor getDescriptor() {
        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    "getDescriptor()", "Entry");
        }

        if (notificationDescriptor == null) {
            // Dead code. Should never happen.
            if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
                MODELMBEAN_LOGGER.logp(Level.FINER,
                        ModelMBeanNotificationInfo.class.getName(),
                        "getDescriptor()", "Descriptor value is null, " +
                        "setting descriptor to default values");
            }
            notificationDescriptor = createDefaultDescriptor();
        }

        return((Descriptor)notificationDescriptor.clone());
    }

    /**
     * Sets associated Descriptor (full replace) for the
     * ModelMBeanNotificationInfo If the new Descriptor is null,
     * then the associated Descriptor reverts to a default
     * descriptor.  The Descriptor is validated before it is
     * assigned.  If the new Descriptor is invalid, then a
     * RuntimeOperationsException wrapping an
     * IllegalArgumentException is thrown.
     *
     * @param inDescriptor replaces the Descriptor associated with the
     * ModelMBeanNotification interface
     *
     * @exception RuntimeOperationsException Wraps an
     * {@link IllegalArgumentException} for invalid Descriptor.
     *
     * @see #getDescriptor
     **/
    public void setDescriptor(Descriptor inDescriptor) {
        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    "setDescriptor(Descriptor)", "Entry");
        }
        applyDescriptor(inDescriptor,"setDescriptor(Descriptor)");
    }

    /**
     * Returns a human readable string containing
     * ModelMBeanNotificationInfo.
     *
     * @return a string describing this object.
     **/
    public String toString() {
        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    "toString()", "Entry");
        }

        final StringBuilder retStr = new StringBuilder();

        retStr.append("ModelMBeanNotificationInfo: ")
            .append(this.getName());

        retStr.append(" ; Description: ")
            .append(this.getDescription());

        retStr.append(" ; Descriptor: ")
            .append(this.getDescriptor());

        retStr.append(" ; Types: ");
        String[] nTypes = this.getNotifTypes();
        for (int i=0; i < nTypes.length; i++) {
            if (i > 0) retStr.append(", ");
            retStr.append(nTypes[i]);
        }
        return retStr.toString();
    }

    /**
     * Creates default descriptor for notification as follows:
     * <pre>descriptorType=notification,
     * name=this.getName(),displayname=this.getName(),severity=6
     * </pre>
     **/
    private final Descriptor createDefaultDescriptor() {
        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    "createDefaultDescriptor()", "Entry");
        }

        return new DescriptorSupport(new
            String[] {"descriptorType=notification",
                      ("name=" + this.getName()),
                      ("displayName=" + this.getName()),
                      "severity=6"});
    }

    /**
     * Tests that the descriptor is valid and adds appropriate default
     * fields not already specified. Field values must be correct for
     * field names.
     * Descriptor must have the same name as the notification,
     * the descriptorType field must be "notification",
     **/
    private boolean isValid(Descriptor inDesc) {
        boolean results = true;
        String badField = "none";

        if (inDesc == null) {
            badField="nullDescriptor";
            return false;
        }

        if (!inDesc.isValid()) {
            // checks for empty descriptors, null,
            // checks for empty name and descriptorType adn valid
            // values for fields.
            badField="invalidDescriptor";
            results = false;
        } else if (!((String)inDesc.getFieldValue("name")).
                   equalsIgnoreCase(this.getName())) {
            badField="name";
            results = false;
        } else if (! ((String)inDesc.getFieldValue("descriptorType")).
                   equalsIgnoreCase("notification")) {
            badField="descriptorType";
            results = false;
        }

        if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
            MODELMBEAN_LOGGER.logp(Level.FINER,
                    ModelMBeanNotificationInfo.class.getName(),
                    "isValid(Descriptor)",
                    "Returning " + results  +
                    " : Invalid field is " + badField);
        }
        return results;
    }

    /**
     * The following fields will be defaulted if they are not already
     * set:
     * displayName=this.getName(),severity=6
     * @return the given descriptor, possibly modified.
     **/
    private final Descriptor setDefaults(Descriptor descriptor) {
        if ((descriptor.getFieldValue("displayName")) == null) {
            descriptor.setField("displayName",this.getName());
        }
        if ((descriptor.getFieldValue("severity")) == null) {
            descriptor.setField("severity","6");
        }
        return descriptor;
    }

    /**
     * Set the given descriptor as this.notificationDescriptor.
     * Creates a default descriptor if the given descriptor is null.
     * If the given descriptor is null, check its validity.
     * If it is valid, clones it and set the defaults fields
     * "displayName" and "severity", if not present.
     * If it is not valid, throws an exception.
     * This method is called both by the constructors and by
     * setDescriptor().
     * @see #setDefaults
     * @see #setDescriptor
     **/
    private final void applyDescriptor(Descriptor descriptor,
                                       String ftag) {
        if (descriptor == null) {
            if (MODELMBEAN_LOGGER.isLoggable(Level.FINER)) {
                MODELMBEAN_LOGGER.logp(Level.FINER,
                        ModelMBeanNotificationInfo.class.getName(), ftag,
                        "Received null for new descriptor value, " +
                        "setting descriptor to default values");
            }
            notificationDescriptor = createDefaultDescriptor();
        } else if (isValid(descriptor)) {
            notificationDescriptor =
                setDefaults((Descriptor)descriptor.clone());
        } else {
            throw new RuntimeOperationsException(new
                IllegalArgumentException(
                "Invalid descriptor passed in parameter"),
                "Exception occurred in ModelMBeanNotificationInfo " + ftag);
        }
    }


    /**
     * Deserializes a {@link ModelMBeanNotificationInfo} from an
     * {@link ObjectInputStream}.
     **/
    private void readObject(ObjectInputStream in)
        throws IOException, ClassNotFoundException {
        // New serial form ignores extra field "currClass"
        in.defaultReadObject();
    }


    /**
     * Serializes a {@link ModelMBeanNotificationInfo} to an
     * {@link ObjectOutputStream}.
     **/
    private void writeObject(ObjectOutputStream out)
        throws IOException {
        if (compat) {
            // Serializes this instance in the old serial form
            //
            ObjectOutputStream.PutField fields = out.putFields();
            fields.put("notificationDescriptor", notificationDescriptor);
            fields.put("currClass", currClass);
            out.writeFields();
        } else {
            // Serializes this instance in the new serial form
            //
            out.defaultWriteObject();
        }
    }

}
