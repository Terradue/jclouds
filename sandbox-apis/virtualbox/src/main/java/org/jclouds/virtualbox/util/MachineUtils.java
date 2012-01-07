/*
 * Licensed to jclouds, Inc. (jclouds) under one or more
 * contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  jclouds licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jclouds.virtualbox.util;

import static org.jclouds.scriptbuilder.domain.Statements.call;
import static org.jclouds.scriptbuilder.domain.Statements.findPid;
import static org.jclouds.scriptbuilder.domain.Statements.kill;
import static org.jclouds.scriptbuilder.domain.Statements.newStatementList;

import org.jclouds.scriptbuilder.domain.Statement;
import org.jclouds.util.Throwables2;
import org.virtualbox_4_1.IMachine;
import org.virtualbox_4_1.ISession;
import org.virtualbox_4_1.LockType;
import org.virtualbox_4_1.SessionState;
import org.virtualbox_4_1.VBoxException;
import org.virtualbox_4_1.VirtualBoxManager;

import com.google.common.base.Function;

/**
 * Utilities for executing functions on a VirtualBox machine.
 *
 * @author Adrian Cole, Mattias Holmqvist
 */
public class MachineUtils {

   public static <T> T applyForMachine(VirtualBoxManager manager, final String machineId, final Function<IMachine, T> function) {
      final IMachine immutableMachine = manager.getVBox().findMachine(machineId);
      return new Function<IMachine, T>() {
         @Override
         public T apply(IMachine machine) {
            return function.apply(machine);
         }

         @Override
         public String toString() {
            return function.toString();
         }
      }.apply(immutableMachine);
   }

   /**
    * Locks the machine and executes the given function using the machine matching the given id.
    * Since the machine is locked it is possible to perform some modifications to the IMachine.
    * <p/>
    * Unlocks the machine before returning.
    *
    * @param manager   the VirtualBoxManager
    * @param type      the kind of lock to use when initially locking the machine.
    * @param machineId the id of the machine
    * @param function  the function to execute
    * @return the result from applying the function to the machine.
    */
   public static <T> T lockMachineAndApply(VirtualBoxManager manager, final LockType type, final String machineId,
                                           final Function<IMachine, T> function) {
      return lockSessionOnMachineAndApply(manager, type, machineId, new Function<ISession, T>() {

         @Override
         public T apply(ISession session) {
            return function.apply(session.getMachine());
         }

         @Override
         public String toString() {
            return function.toString();
         }

      });
   }
   
   /**
    * Locks the machine and executes the given function using the current session.
    * Since the machine is locked it is possible to perform some modifications to the IMachine.
    * <p/>
    * Unlocks the machine before returning.
    *
    * @param manager   the VirtualBoxManager
    * @param type      the kind of lock to use when initially locking the machine.
    * @param machineId the id of the machine
    * @param function  the function to execute
    * @return the result from applying the function to the session.
    */
   public static <T> T lockSessionOnMachineAndApply(VirtualBoxManager manager, LockType type, String machineId,
                                                    Function<ISession, T> function) {
      try {
         ISession session = manager.getSessionObject();
         IMachine immutableMachine = manager.getVBox().findMachine(machineId);
         immutableMachine.lockMachine(session, type);
         try {
            return function.apply(session);
         } finally {
            session.unlockMachine();
         }
      } catch (VBoxException e) {
         throw new RuntimeException(String.format("error applying %s to %s with %s lock: %s", function, machineId,
                 type, e.getMessage()), e);
      }
   }
   
   /**
    * Locks the machine and executes the given function using the current session, if the machine is registered.
    * Since the machine is locked it is possible to perform some modifications to the IMachine.
    * <p/>
    * Unlocks the machine before returning.
    *
    * @param manager   the VirtualBoxManager
    * @param type      the kind of lock to use when initially locking the machine.
    * @param machineId the id of the machine
    * @param function  the function to execute
    * @return the result from applying the function to the session.
    */
   public static <T> T lockMachineAndApplyOrReturnNullIfNotRegistered(VirtualBoxManager manager, LockType type, String machineId,
                                                    Function<IMachine, T> function) {
      try {
         return lockMachineAndApply(manager, type, machineId, function);
      } catch (RuntimeException e) {
         VBoxException vbex = Throwables2.getFirstThrowableOfType(e, VBoxException.class);
         if (vbex != null && vbex.getMessage().indexOf("not find a registered") == -1)
            throw e;
         return null;
      }
   }

   /**
    * Unlocks the machine and executes the given function using the machine matching the given id.
    * Since the machine is unlocked it is possible to delete the IMachine.
    * <p/>
    * 
    *<h3>Note!</h3> Currently, this can only unlock the machine, if the lock was created in the
    * current session.
    * 
    * @param manager
    *           the VirtualBoxManager
    * @param machineId
    *           the id of the machine
    * @param function
    *           the function to execute
    * @return the result from applying the function to the machine.
    */
   public static <T> T unlockMachineAndApply(VirtualBoxManager manager, final String machineId,
            final Function<IMachine, T> function) {
      try {
         ISession session = manager.getSessionObject();
         IMachine immutableMachine = manager.getVBox().findMachine(machineId);
         SessionState state = immutableMachine.getSessionState();
         if (state.equals(SessionState.Locked))
            session.unlockMachine();
         //TODO: wire this in
         Statement kill = newStatementList(call("default"), findPid(machineId), kill());

         return function.apply(immutableMachine);

      } catch (VBoxException e) {
         throw new RuntimeException(String.format("error applying %s to %s: %s", function, machineId, e.getMessage()),
                  e);
      }
   }

   /**
    * Unlocks the machine and executes the given function, if the machine is registered.
    * Since the machine is unlocked it is possible to delete the machine.
    * <p/>
    *
    * @param manager   the VirtualBoxManager
    * @param machineId the id of the machine
    * @param function  the function to execute
    * @return the result from applying the function to the session.
    */
   public static <T> T unlockMachineAndApplyOrReturnNullIfNotRegistered(VirtualBoxManager manager, String machineId,
                                                    Function<IMachine, T> function) {
      try {
         return unlockMachineAndApply(manager, machineId, function);
      } catch (RuntimeException e) {
         VBoxException vbex = Throwables2.getFirstThrowableOfType(e, VBoxException.class);
         if (vbex != null && vbex.getMessage().indexOf("not find a registered") == -1)
            throw e;
         return null;
      }
   }
}
