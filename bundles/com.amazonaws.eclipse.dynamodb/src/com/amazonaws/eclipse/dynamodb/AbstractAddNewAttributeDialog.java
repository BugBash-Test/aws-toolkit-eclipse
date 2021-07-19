/*
 * Copyright 2013 Amazon Technologies, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *    http://aws.amazon.com/apache2.0
 *
 * This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and
 * limitations under the License.
 */
package com.amazonaws.eclipse.dynamodb;

import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.ModifyEvent;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Text;

public abstract class AbstractAddNewAttributeDialog extends MessageDialog {

    String newAttributeName = "";

    public String getNewAttributeName() {
        return newAttributeName.trim();
    }

    protected AbstractAddNewAttributeDialog() {
        super(Display.getCurrent().getActiveShell(), "Enter New Attribute Name", null, "Enter a new attribute name", MessageDialog.NONE, new String[] { "OK", "Cancel" }, 0);
    }

    @Override
    protected Control createCustomArea(Composite parent) {
        final Text text = new Text(parent, SWT.BORDER);
        GridDataFactory.fillDefaults().grab(true, false).span(2, 1).applyTo(text);
        text.addModifyListener(new ModifyListener() {

            @Override
            public void modifyText(ModifyEvent e) {
                newAttributeName = text.getText();
                validate();
            }
        });

        return parent;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        super.createButtonsForButtonBar(parent);
        validate();
    }
    
    
     private final class LoadEnvironmentsThread extends CancelableThread {
        @Override
        public void run() {
            final List<EnvironmentDescription> environments = new ArrayList<>();
            try {
                environments.addAll(elasticBeanstalkClient.describeEnvironments().getEnvironments());
            } catch (Exception e) {
                if (isServiceSignUpException(e)) {
                    StatusManager.getManager().handle(newServiceSignUpErrorStatus(e), StatusManager.SHOW | StatusManager.LOG);
                } else {
                    Status status = new Status(Status.ERROR, ElasticBeanstalkPlugin.PLUGIN_ID,
                        "Unable to load existing environments: " + e.getMessage(), e);
                    StatusManager.getManager().handle(status, StatusManager.LOG);
                }
                setRunning(false);
                return;
            }

            Display.getDefault().asyncExec(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<String> environmentNames = new ArrayList<>();
                        for ( EnvironmentDescription environment : environments ) {
                            // Skip any terminated environments, since we can safely reuse their names
                            if ( isEnvironmentTerminated(environment) ) {
                                continue;
                            }
                            environmentNames.add(environment.getEnvironmentName());
                        }
                        Collections.sort(environmentNames);

                        synchronized (LoadEnvironmentsThread.this) {
                            if ( !isCanceled() ) {
                                existingEnvironmentNames.clear();
                                existingEnvironmentNames.addAll(environmentNames);
                                environmentNamesLoaded.setValue(true);
                                runValidators();
                            }
                        }
                    } finally {
                        setRunning(false);
                    }
                }
            });
        }
    }

    abstract public void validate();

}


