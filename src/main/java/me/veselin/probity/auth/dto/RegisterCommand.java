package me.veselin.probity.auth.dto;

public record RegisterCommand(String username, String email, String password) {}
