/*
 * Copyright 2015 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.netflix.fenzo.plugins;

import com.netflix.fenzo.ConstraintEvaluator;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.TaskTracker;
import com.netflix.fenzo.TaskTrackerState;
import com.netflix.fenzo.VMTaskFitnessCalculator;
import com.netflix.fenzo.VirtualMachineCurrentState;
import com.netflix.fenzo.functions.Func1;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @warn class description missing
 */
public class BalancedHostAttrConstraint implements ConstraintEvaluator {
    private final String name;
    private final Func1<String, Set<String>> coTasksGetter;
    private final String hostAttributeName;
    private final int expectedValues;

    public BalancedHostAttrConstraint(Func1<String, Set<String>> coTasksGetter, String hostAttributeName, int expectedValues) {
        this.coTasksGetter = coTasksGetter;
        this.hostAttributeName = hostAttributeName;
        this.name = BalancedHostAttrConstraint.class.getName()+"-"+hostAttributeName;
        this.expectedValues = expectedValues;
    }

    /**
     * @warn method description missing
     *
     * @return
     */
    @Override
    public String getName() {
        return name;
    }

    /**
     * @warn method description missing
     * @warn parameter descriptions missing
     *
     * @param taskRequest
     * @param targetVM
     * @param taskTrackerState
     * @return
     */
    @Override
    public Result evaluate(TaskRequest taskRequest, VirtualMachineCurrentState targetVM, TaskTrackerState taskTrackerState) {
        Set<String> coTasks = coTasksGetter.call(taskRequest.getId());
        String targetHostAttrVal = AttributeUtilities.getAttrValue(targetVM.getCurrAvailableResources(), hostAttributeName);
        if(targetHostAttrVal==null || targetHostAttrVal.isEmpty()) {
            return new Result(false, hostAttributeName + " attribute unavailable on host " + targetVM.getCurrAvailableResources().hostname());
        }
        Map<String, Integer> usedAttribsMap = null;
        try {
            usedAttribsMap = getUsedAttributesMap(coTasks, taskTrackerState);
        } catch (Exception e) {
            return new Result(false, e.getMessage());
        }
        final Integer integer = usedAttribsMap.get(targetHostAttrVal);
        if(integer==null || usedAttribsMap.isEmpty())
            return new Result(true, "");
        int min=Integer.MAX_VALUE;
        int max=Integer.MIN_VALUE;
        for(Integer i: usedAttribsMap.values()) {
            min = Math.min(min, i);
            max = Math.max(max, i);
        }
        min = expectedValues>usedAttribsMap.size()? 0 : min;
        if(min == max || integer<max)
            return new Result(true, "");
        return new Result(false, "Would further imbalance by host attribute " + hostAttributeName);
    }

    private Map<String, Integer> getUsedAttributesMap(Set<String> coTasks, TaskTrackerState taskTrackerState) throws Exception {
        Map<String, Integer> usedAttribsMap = new HashMap<>();
        for(String coTask: coTasks) {
            TaskTracker.ActiveTask activeTask = taskTrackerState.getAllRunningTasks().get(coTask);
            if(activeTask==null)
                activeTask = taskTrackerState.getAllCurrentlyAssignedTasks().get(coTask);
            if(activeTask!=null) {
                String usedAttrVal = AttributeUtilities.getAttrValue(activeTask.getTotalLease(), hostAttributeName);
                if(usedAttrVal==null || usedAttrVal.isEmpty())
                    throw new Exception(hostAttributeName+" attribute unavailable on host " + activeTask.getTotalLease().hostname() +
                            " running co-task " + coTask); // indicate missing attribute in host
                if(usedAttribsMap.get(usedAttrVal)==null)
                    usedAttribsMap.put(usedAttrVal, 1);
                else
                    usedAttribsMap.put(usedAttrVal, usedAttribsMap.get(usedAttrVal)+1);
            }
        }
        return usedAttribsMap;
    }

    /**
     * @warn method description missing
     *
     * @return
     */
    public VMTaskFitnessCalculator asSoftConstraint() {
        return new VMTaskFitnessCalculator() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public double calculateFitness(TaskRequest taskRequest, VirtualMachineCurrentState targetVM, TaskTrackerState taskTrackerState) {
                String targetHostAttrVal = AttributeUtilities.getAttrValue(targetVM.getCurrAvailableResources(), hostAttributeName);
                if(targetHostAttrVal==null || targetHostAttrVal.isEmpty()) {
                    return 0.0;
                }
                Set<String> coTasks = coTasksGetter.call(taskRequest.getId());
                Map<String, Integer> usedAttribsMap = null;
                try {
                    usedAttribsMap = getUsedAttributesMap(coTasks, taskTrackerState);
                } catch (Exception e) {
                    return 0.0;
                }
                final Integer integer = usedAttribsMap.get(targetHostAttrVal);
                if(integer==null)
                    return 1.0;
                if(usedAttribsMap.isEmpty())
                    return 1.0;
                double avg=0.0;
                for(Integer i: usedAttribsMap.values())
                    avg += i;
                avg = Math.ceil(avg+1 / Math.max(expectedValues, usedAttribsMap.size()));
                if(integer<=avg)
                    return (avg-(double)integer) / avg;
                return 0.0;
            }
        };
    }
}