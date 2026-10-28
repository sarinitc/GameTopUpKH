package org.example.gametopupkh.auth.passwordreset;

/** Public password reset workflow. Never returns tokens or confirms account existence. */
public interface PasswordResetService {

    void requestReset(ForgotPasswordRequest request, String ipAddress);

    void resetPassword(ResetPasswordRequest request, String ipAddress);
}
