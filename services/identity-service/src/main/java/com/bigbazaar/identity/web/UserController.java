package com.bigbazaar.identity.web;

import com.bigbazaar.identity.user.UserDtos.AddressRequest;
import com.bigbazaar.identity.user.UserDtos.AddressResponse;
import com.bigbazaar.identity.user.UserDtos.UserProfile;
import com.bigbazaar.identity.user.UserService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "Users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserProfile me(@AuthenticationPrincipal Jwt jwt) {
        return userService.profile(CurrentUser.id(jwt));
    }

    @GetMapping("/addresses")
    public List<AddressResponse> addresses(@AuthenticationPrincipal Jwt jwt) {
        return userService.listAddresses(CurrentUser.id(jwt));
    }

    @PostMapping("/addresses")
    @ResponseStatus(HttpStatus.CREATED)
    public AddressResponse addAddress(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody AddressRequest request) {
        return userService.addAddress(CurrentUser.id(jwt), request);
    }

    @DeleteMapping("/addresses/{addressId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAddress(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID addressId) {
        userService.deleteAddress(CurrentUser.id(jwt), addressId);
    }
}
