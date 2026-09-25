package com.example.videolingo.config;

import com.example.videolingo.entity.Role;
import com.example.videolingo.entity.User;
import com.example.videolingo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdminSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${seed.admin.enabled:true}")
    private boolean enabled;

    @Value("${seed.admin.username:admin}")
    private String adminUsername;

    @Value("${seed.admin.password:admin123}")
    private String adminPassword;

    @Override
    public void run(String... args) {
        if (!enabled || userRepository.existsByUsername(adminUsername)) {
            return;
        }
        userRepository.save(User.builder()
                .username(adminUsername)
                .password(passwordEncoder.encode(adminPassword))
                .role(Role.ADMIN)
                .enabled(true)
                .build());
        log.info("Seeded default admin user '{}' (change this password before deploying anywhere real)", adminUsername);
    }
}
