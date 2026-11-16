package com.onward.orders.model;

/**
 * Which server answered: on ECS Fargate this is the short task ID, Availability Zone and private IP.
 */
public record ServerIdentity(String serverId, String availabilityZone, String privateIp) {

    /** Short label shown in the UI and stored on orders, e.g. "3f2a9c41 · eu-west-1a". */
    public String label() {
        return serverId + " · " + availabilityZone;
    }
}
