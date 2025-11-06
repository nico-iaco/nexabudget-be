package it.iacovelli.nexabudgetbe.service;

import it.iacovelli.nexabudgetbe.dto.AuthDto;
import it.iacovelli.nexabudgetbe.dto.UserDto;
import it.iacovelli.nexabudgetbe.model.User;
import it.iacovelli.nexabudgetbe.security.JwtTokenProvider;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {

    private final UserService userService;
    private final JwtTokenProvider jwtUtil;
    private final AuthenticationManager authenticationManager;

    public AuthService(UserService userService, PasswordEncoder passwordEncoder, JwtTokenProvider jwtUtil, AuthenticationManager authenticationManager) {
        this.userService = userService;
        this.jwtUtil = jwtUtil;
        this.authenticationManager = authenticationManager;
        System.out.println("AuthService inizializzato con encoder: " + passwordEncoder.getClass().getName());
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest loginRequest) {
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        loginRequest.getUsername(),
                        loginRequest.getPassword()
                )
        );

        SecurityContextHolder.getContext().setAuthentication(authentication);

        User userDetails = (User) authentication.getPrincipal();
        String token = jwtUtil.generateToken(userDetails);

        return AuthDto.AuthResponse.builder()
                .token(token)
                .userId(userDetails.getId())
                .username(userDetails.getUsername())
                .build();
    }

    public AuthDto.AuthResponse register(UserDto.UserRequest userRequest) {

        // Creazione del nuovo utente
        User user = User.builder()
                .username(userRequest.getUsername())
                .email(userRequest.getEmail())
                .passwordHash(userRequest.getPassword())
                .build();

        User savedUser = userService.createUser(user);

        String token = jwtUtil.generateToken(savedUser);

        return AuthDto.AuthResponse.builder()
                .token(token)
                .userId(savedUser.getId())
                .username(savedUser.getUsername())
                .build();
    }
}
