import 'package:flutter/material.dart';
import 'package:supabase_flutter/supabase_flutter.dart' show OAuthProvider;

import '../core/theme.dart';
import '../services/auth.dart';
import '../widgets/glass.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _signup = false;
  final _email = TextEditingController();
  final _password = TextEditingController();
  bool _busy = false;
  String? _error;
  String? _info;

  @override
  void initState() {
    super.initState();
    Auth.instance.addListener(_signedIn);
  }

  @override
  void dispose() {
    Auth.instance.removeListener(_signedIn);
    _email.dispose();
    _password.dispose();
    super.dispose();
  }

  void _signedIn() {
    if (Auth.instance.signedIn && mounted) Navigator.of(context).maybePop();
  }

  Future<void> _submit() async {
    final email = _email.text.trim();
    final pw = _password.text;
    if (!RegExp(r'^\S+@\S+\.\S+$').hasMatch(email)) return setState(() => _error = 'Enter a valid email address.');
    if (pw.length < 6) return setState(() => _error = 'Use a password of at least 6 characters.');
    setState(() {
      _busy = true;
      _error = null;
      _info = null;
    });
    try {
      if (_signup) {
        final confirm = await Auth.instance.signUp(email, pw);
        if (confirm && mounted) setState(() => _info = 'Check your inbox and open the confirmation link, then log in.');
      } else {
        await Auth.instance.signIn(email, pw);
      }
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _oauth(OAuthProvider p) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await Auth.instance.oauth(p);
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
    if (mounted) setState(() => _busy = false);
  }

  Future<void> _reset() async {
    final email = _email.text.trim();
    if (!RegExp(r'^\S+@\S+\.\S+$').hasMatch(email)) return setState(() => _error = 'Enter your email above first.');
    try {
      await Auth.instance.resetPassword(email);
      if (mounted) setState(() => _info = 'Password reset link sent. Check your inbox.');
    } catch (e) {
      if (mounted) setState(() => _error = friendlyError(e));
    }
  }

  InputDecoration _dec(String hint, IconData icon) => InputDecoration(
        hintText: hint,
        hintStyle: TextStyle(color: C.w(0.4)),
        prefixIcon: Icon(icon, size: 20, color: C.w(0.5)),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 15),
        enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: C.w(0.2))),
        focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide(color: C.w(0.6))),
      );

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.of(context).padding.top;
    return Scaffold(
      backgroundColor: C.bg,
      body: Stack(children: [
        ListView(padding: EdgeInsets.fromLTRB(20, top + 70, 20, 40), children: [
          Center(child: Image.asset('assets/logo.png', height: 44, errorBuilder: (_, __, ___) => const Text('AniBiar TV', style: TextStyle(fontSize: 26, fontWeight: FontWeight.w800)))),
          const SizedBox(height: 26),
          Container(
            padding: const EdgeInsets.all(20),
            decoration: BoxDecoration(borderRadius: BorderRadius.circular(20), border: Border.all(color: C.w(0.2))),
            child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              GlassSwitch<bool>(value: _signup, onChanged: (v) => setState(() {
                    _signup = v;
                    _error = null;
                    _info = null;
                  }), options: const [(false, 'Log in'), (true, 'Sign up')], height: 40),
              const SizedBox(height: 20),
              Text(_signup ? 'Create your account' : 'Welcome back', style: const TextStyle(fontSize: 21, fontWeight: FontWeight.w800)),
              const SizedBox(height: 4),
              Text('Keep your history, favorites and watchlist on every device.', style: TextStyle(fontSize: 13.5, color: C.w(0.55))),
              const SizedBox(height: 18),
              TextField(controller: _email, keyboardType: TextInputType.emailAddress, autocorrect: false, enabled: !_busy, style: const TextStyle(fontSize: 16), decoration: _dec('Email', Icons.mail_outline_rounded)),
              const SizedBox(height: 12),
              TextField(controller: _password, obscureText: true, enabled: !_busy, onSubmitted: (_) => _submit(), style: const TextStyle(fontSize: 16), decoration: _dec('Password', Icons.lock_outline_rounded)),
              if (!_signup)
                Align(alignment: Alignment.centerRight, child: Padding(padding: const EdgeInsets.only(top: 10), child: GestureDetector(onTap: _reset, child: Text('Forgot password?', style: TextStyle(fontSize: 12.5, color: C.w(0.6), decoration: TextDecoration.underline))))),
              if (_error != null)
                Container(
                  margin: const EdgeInsets.only(top: 14),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  decoration: BoxDecoration(color: const Color(0x1AEF4444), borderRadius: BorderRadius.circular(12), border: Border.all(color: const Color(0x4DF87171))),
                  child: Text(_error!, style: const TextStyle(fontSize: 12.5, color: Color(0xFFFCA5A5))),
                ),
              if (_info != null)
                Container(
                  margin: const EdgeInsets.only(top: 14),
                  padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                  decoration: BoxDecoration(color: C.w(0.05), borderRadius: BorderRadius.circular(12), border: Border.all(color: C.w(0.2))),
                  child: Text(_info!, style: TextStyle(fontSize: 12.5, color: C.w(0.85))),
                ),
              const SizedBox(height: 18),
              GlassButton(label: _signup ? 'Create account' : 'Log in', kind: GlassKind.white, height: 48, busy: _busy, expand: true, onTap: _submit),
              const SizedBox(height: 18),
              Row(children: [
                Expanded(child: Divider(color: C.w(0.15))),
                Padding(padding: const EdgeInsets.symmetric(horizontal: 12), child: Text('or', style: TextStyle(fontSize: 12, color: C.w(0.5)))),
                Expanded(child: Divider(color: C.w(0.15))),
              ]),
              const SizedBox(height: 18),
              GlassButton(label: 'Continue with Google', icon: Icons.g_mobiledata_rounded, height: 46, expand: true, onTap: _busy ? null : () => _oauth(OAuthProvider.google)),
              const SizedBox(height: 10),
              GlassButton(label: 'Continue with Discord', icon: Icons.forum_rounded, kind: GlassKind.accent, height: 46, expand: true, onTap: _busy ? null : () => _oauth(OAuthProvider.discord)),
            ]),
          ),
        ]),
        Positioned(top: top + 8, left: 12, child: GlassIconButton(icon: Icons.arrow_back_rounded, size: 42, onTap: () => Navigator.of(context).maybePop(), tooltip: 'Back')),
      ]),
    );
  }
}
