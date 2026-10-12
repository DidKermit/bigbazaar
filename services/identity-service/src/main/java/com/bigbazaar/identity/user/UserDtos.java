package com.bigbazaar.identity.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class UserDtos {

    public record UserProfile(UUID id, String name, String email, String phone, UserStatus status,
                              List<String> roles, Instant createdAt) {
        static UserProfile from(User user) {
            return new UserProfile(user.getId(), user.getName(), user.getEmail(), user.getPhone(), user.getStatus(),
                    user.getRoles().stream().map(Enum::name).sorted().toList(), user.getCreatedAt());
        }
    }

    public record AddressRequest(
            @Size(max = 40) String label,
            @NotBlank @Size(max = 120) String recipient,
            @NotBlank @Pattern(regexp = "^\\+?[0-9]{10,15}$") String phone,
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 100) String state,
            @NotBlank @Pattern(regexp = "^[0-9A-Za-z -]{3,12}$") String postalCode,
            @Pattern(regexp = "^[A-Z]{2}$") String country,
            boolean makeDefault) {
    }

    public record AddressResponse(UUID id, String label, String recipient, String phone, String line1, String line2,
                                  String city, String state, String postalCode, String country, boolean isDefault) {
        static AddressResponse from(Address a) {
            return new AddressResponse(a.getId(), a.getLabel(), a.getRecipient(), a.getPhone(), a.getLine1(),
                    a.getLine2(), a.getCity(), a.getState(), a.getPostalCode(), a.getCountry(), a.isDefaultAddress());
        }
    }

    public record StatusChangeRequest(@NotBlank @Size(min = 5, max = 500) String reason) {
    }

    private UserDtos() {
    }
}
