package com.bigbazaar.identity.config;

import com.bigbazaar.identity.user.Role;
import com.bigbazaar.identity.user.User;
import com.bigbazaar.identity.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Creates the first admin from environment variables, since admins cannot self-register.
 * Does nothing when the variables are unset or the account already exists.
 */
@Component
class BootstrapAdmin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdmin.class);

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final String email;
    private final String password;

    BootstrapAdmin(UserRepository users, PasswordEncoder passwordEncoder, TransactionTemplate tx,
                   @Value("${bigbazaar.bootstrap-admin.email:}") String email,
                   @Value("${bigbazaar.bootstrap-admin.password:}") String password) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.email = email;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!StringUtils.hasText(email) || !StringUtils.hasText(password)) {
            return;
        }
        try {
            tx.executeWithoutResult(status -> {
                if (users.existsActiveByEmail(email)) {
                    return;
                }
                User admin = User.register("Platform Admin", email, "0000000000", passwordEncoder.encode(password));
                admin.grantRole(Role.ADMIN);
                users.save(admin);
                log.info("Bootstrap admin created userId={}", admin.getId());
            });
        } catch (DataIntegrityViolationException e) {
            log.info("Bootstrap admin already created by another replica");
        }
    }
}
