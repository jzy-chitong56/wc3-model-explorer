package org.example.model;

/**
 * A configured external 3D program.
 * @param name    display name (e.g. "Blender")
 * @param command command template — use {@code {file}} as placeholder for the model path
 */
public record ExternalProgram(String name, String command) {}
