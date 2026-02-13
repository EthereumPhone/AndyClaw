package org.ethereumphone.andyclaw;

interface IAndyClawSkill {
    String getManifestJson();
    String execute(String tool, String paramsJson, String tier);
}
