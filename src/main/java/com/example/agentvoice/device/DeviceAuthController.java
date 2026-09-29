package com.example.agentvoice.device;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@org.springframework.context.annotation.Profile("!scaffold")
@RequestMapping("/device/auth")
public class DeviceAuthController {
    private final DeviceAuthService auth;
    public DeviceAuthController(DeviceAuthService auth){this.auth=auth;}
    /** 为设备申请用于签名认证的一次性挑战。 */
    @PostMapping("/challenge") public DeviceAuthService.Challenge challenge(@Valid @RequestBody ChallengeRequest request){return auth.challenge(request.deviceId(),request.sn());}
    /** 校验设备挑战签名并返回访问令牌。 */
    @PostMapping("/token") public DeviceAuthService.Token token(@Valid @RequestBody TokenRequest request){return auth.token(new DeviceAuthService.TokenRequest(request.deviceId(),request.sn(),request.challengeId(),request.nonce(),request.issuedAt(),request.signature()));}
    public record ChallengeRequest(@NotBlank @Size(max=96) String deviceId,@NotBlank @Size(max=128) String sn){}
    public record TokenRequest(@NotBlank @Size(max=96) String deviceId,@NotBlank @Size(max=128) String sn,@NotBlank @Pattern(regexp="[0-9a-fA-F-]{36}") String challengeId,@NotBlank @Pattern(regexp="[A-Za-z0-9_-]{40,48}") String nonce,@jakarta.validation.constraints.Positive long issuedAt,@NotBlank @Pattern(regexp="[A-Fa-f0-9]{64}") String signature){}
}
